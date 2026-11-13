package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.*
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.transfer.*
import org.desktop2fa.desktop.watch.vault.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferSessionTest {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        fun await() { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
    }
    private class Storage : VaultSessionStorage(File("synthetic-transfer-${System.nanoTime()}.d2fa"), { true }) {
        @Volatile var current = Vault(VaultData(2))
        @Volatile var beforePrepare: () -> Unit = {}
        @Volatile var beforeCommit: () -> Unit = {}
        @Volatile var atSave: () -> Unit = {}
        @Volatile var beforeStatus: () -> Unit = {}
        override fun transferStatus(transferId: String, sha256: String, password: String): org.desktop2fa.desktop.watch.model.TransferReceipt? {
            beforeStatus(); return TransferPlanner.status(current.data, transferId, sha256)
        }
        @Volatile var writes = 0
        @Volatile var migrations = 0
        @Volatile var beforeMigration: () -> Unit = {}
        override fun activateTransfer(baseline: VaultData, password: String): VaultMigrationResult {
            beforeMigration()
            check(baseline == current.data) { "Synthetic stale migration" }
            migrations++
            current = Vault(baseline.copy(version = 2))
            return VaultMigrationResult(VaultSaveOutcome.COMMITTED, current)
        }
        override fun unlock(password: String) = current
        override fun prepareTransfer(offer: TransferOffer, password: String): TransferPreparation {
            beforePrepare(); return TransferPlanner.prepare(current.data, offer)
        }
        override fun commitTransfer(candidate: TransferAdditionCandidate, password: String, approval: TransferSaveGate): TransferCommitResult {
            beforeCommit()
            return commitApprovedTransfer(candidate, File("synthetic-unused"), password, approval, object : TransferCommitIO() {
                override fun load(file: File, password: String) = current
                override fun inspect(file: File) = VaultPresence.PRESENT
                override fun mainPresent(file: File) = true
                override fun save(vault: Vault, file: File, password: String): VaultSaveOutcome {
                    atSave(); writes++; current = vault; return VaultSaveOutcome.COMMITTED
                }
            })
        }
    }
    private class Fixture : AutoCloseable {
        val storage = Storage()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val job = SupervisorJob()
        val session = VaultSession(CoroutineScope(job + ui), storage, io)
        fun <T> ui(action: VaultSession.() -> T): T = runBlocking { withContext(ui) { session.action() } }
        fun idle() = runBlocking { withTimeout(5_000) { while (ui { operationPending }) delay(1) } }
        fun unlock() { ui { passwordInput = "Synthetic-only-42!"; unlock() }; idle() }
        fun prepare(offer: TransferOffer = offer()): Pair<Any, TransferAdditionCandidate> {
            val token = ui { requireNotNull(beginTransfer()) }
            ui { assertTrue(prepareTransfer(token, offer)) }; idle()
            return token to ui { requireNotNull(transferReview) }
        }
        override fun close() {
            ui { dispose() }; runBlocking { withTimeout(5_000) { job.cancelAndJoin() } }; io.close(); ui.close()
        }
    }
    @Test fun explicitApprovalPublishesAndReplayNeedsNoSecondWrite() = Fixture().use { f ->
        f.unlock(); val (token, candidate) = f.prepare()
        assertEquals(0, f.storage.writes)
        f.ui { assertTrue(approveTransfer(token, candidate)) }; f.idle()
        f.ui { assertTrue(transferResult is TransferCommitResult.Saved); assertEquals(1, activeVault!!.entries.size) }
        val replayToken = f.ui { requireNotNull(beginTransfer()) }
        f.ui { assertTrue(prepareTransfer(replayToken, offer())) }; f.idle()
        f.ui { assertNull(transferReview); assertTrue(transferResult is TransferCommitResult.Saved) }
        assertEquals(1, f.storage.writes)
    }
    @Test fun v1CannotEnterTransferOrMigrateImplicitly() = Fixture().use { f ->
        f.storage.current = Vault.create(); f.unlock()
        f.ui { assertNull(beginTransfer()) }; assertEquals(1, f.storage.current.data.version)
    }
    @Test fun cancelDuringPreparationCannotPublishAReview() = Fixture().use { f ->
        f.unlock(); val gate = Gate(); f.storage.beforePrepare = { gate.block() }
        val token = f.ui { requireNotNull(beginTransfer()) }
        f.ui { prepareTransfer(token, offer()) }; gate.await()
        try { f.ui { assertEquals(TransferCancellation.CANCELLED, cancelTransfer(token)) } }
        finally { gate.release.countDown() }
        f.idle(); f.ui { assertNull(transferReview); assertSame(TransferCommitResult.Cancelled, transferResult) }
        assertEquals(0, f.storage.writes)
    }
    @Test fun lockDuringPreparationInvalidatesOldOwner() = Fixture().use { f ->
        f.unlock(); val gate = Gate(); f.storage.beforePrepare = { gate.block() }
        val token = f.ui { requireNotNull(beginTransfer()) }
        f.ui { prepareTransfer(token, offer()) }; gate.await()
        try { f.ui { lock(); assertNull(transferReview); assertNull(activeVault) } }
        finally { gate.release.countDown() }
        f.idle(); f.storage.beforePrepare = {}; f.unlock()
        f.ui { assertFalse(prepareTransfer(token, offer())); assertNull(transferReview) }
    }
    @Test fun lockBeforeSaveBoundaryCancelsApproval() = Fixture().use { f ->
        f.unlock(); val (token, candidate) = f.prepare(); val gate = Gate()
        f.storage.beforeCommit = { gate.block() }
        f.ui { approveTransfer(token, candidate) }; gate.await()
        try { f.ui { lock(); assertNull(activeVault); assertNull(transferReview) } }
        finally { gate.release.countDown() }
        f.idle(); assertEquals(0, f.storage.writes)
        f.ui { assertNull(activeVault); assertNull(transferResult); assertEquals(Screen.STORAGE_ERROR, currentScreen) }
    }
    @Test fun lockAfterSaveBoundaryHidesUiWhilePublicationFinishes() = Fixture().use { f ->
        f.unlock(); val (token, candidate) = f.prepare(); val gate = Gate()
        f.storage.atSave = { gate.block() }
        f.ui { approveTransfer(token, candidate) }; gate.await()
        try { f.ui { assertEquals(TransferCancellation.TOO_LATE, cancelTransfer(token)); lock(); assertNull(activeVault) } }
        finally { gate.release.countDown() }
        f.idle(); assertEquals(1, f.storage.writes)
        assertEquals(1, f.storage.current.data.transferReceipts.size)
        f.ui { assertNull(activeVault); assertNull(transferResult); assertEquals(Screen.STORAGE_ERROR, currentScreen) }
    }
    @Test fun changedBaselineRequiresFreshReviewAndNewConsent() = Fixture().use { f ->
        f.unlock(); val batch = offer(); val (token, candidate) = f.prepare(batch)
        f.storage.current = Vault(VaultData(2)).apply { addEntry("other", "synthetic", "AE") }
        f.ui { approveTransfer(token, candidate) }; f.idle()
        f.ui {
            assertSame(TransferCommitResult.Stale, transferResult)
            assertFalse(approveTransfer(token, candidate))
            assertTrue(prepareTransfer(token, batch))
        }; f.idle()
        val replacement = f.ui { requireNotNull(transferReview) }
        assertNotSame(candidate, replacement)
        f.ui { assertFalse(approveTransfer(token, candidate)); assertTrue(approveTransfer(token, replacement)) }; f.idle()
        assertEquals(1, f.storage.writes); assertEquals(2, f.storage.current.entries.size)
    }
    @Test fun repeatedRemoteOfferCannotReplacePendingConsent() = Fixture().use { f ->
        f.unlock(); val (token, candidate) = f.prepare()
        f.ui {
            assertNull(beginTransfer()); assertFalse(prepareTransfer(token, offer()))
            assertSame(candidate, transferReview)
            assertEquals(TransferCancellation.CANCELLED, cancelTransfer(token))
            assertFalse(approveTransfer(token, candidate))
        }; assertEquals(0, f.storage.writes)
    }
    @Test fun pendingTransferBlocksManualAdditionsAndSecondReceiver() = Fixture().use { f ->
        f.unlock(); val (token, _) = f.prepare()
        f.ui {
            addAccount(); assertEquals(Screen.MAIN, currentScreen)
            submitAdditions(listOf(org.desktop2fa.desktop.watch.model.TotpEntry("manual", "synthetic", "AE")))
            assertFalse(operationPending); assertNull(beginTransfer())
            cancelTransfer(token); addAccount(); assertEquals(Screen.ADD_ACCOUNT, currentScreen)
        }; assertEquals(0, f.storage.writes)
    }
    @Test fun disposeAfterSaveBoundaryDoesNotInterruptOrRepublish() = Fixture().use { f ->
        f.unlock(); val (token, candidate) = f.prepare(); val gate = Gate()
        f.storage.atSave = { gate.block() }
        f.ui { approveTransfer(token, candidate) }; gate.await()
        try { f.ui { dispose(); assertNull(activeVault); assertNull(transferReview) } }
        finally { gate.release.countDown() }
        // Disposal intentionally leaves no active UI bookkeeping; drain the I/O owner.
        runBlocking { withContext(f.io) {} }
        assertEquals(1, f.storage.writes)
        f.ui { assertNull(activeVault); assertNull(transferResult); assertNull(cancelTransfer(token)) }
    }
    @Test fun unlockNeverMigratesAndOnlyExactLocalConsentCanActivate() = Fixture().use { f ->
        f.storage.current = Vault.create(); f.unlock()
        assertEquals(0, f.storage.migrations)
        val consent = f.ui { requireNotNull(requestTransferMigration()) }
        assertEquals(0, f.storage.migrations)
        f.ui {
            assertNull(beginTransfer()); assertNull(requestTransferMigration())
            assertFalse(confirmTransferMigration(TransferMigrationReview(VaultData(1))))
            assertTrue(confirmTransferMigration(consent))
        }; f.idle()
        assertEquals(1, f.storage.migrations)
        f.ui {
            assertEquals(2, activeVault!!.data.version); assertNull(transferMigrationReview)
            assertFalse(confirmTransferMigration(consent)); assertNotNull(beginTransfer())
        }
    }
    @Test fun migrationRefusalAndLockDiscardConsent() = Fixture().use { f ->
        f.storage.current = Vault.create(); f.unlock()
        val refused = f.ui { requireNotNull(requestTransferMigration()) }
        f.ui { assertTrue(cancelTransferMigration(refused)); assertFalse(confirmTransferMigration(refused)) }
        val old = f.ui { requireNotNull(requestTransferMigration()) }
        f.ui { lock(); assertNull(transferMigrationReview) }; f.idle(); f.unlock()
        f.ui { assertFalse(confirmTransferMigration(old)); assertNull(transferMigrationReview) }
        assertEquals(0, f.storage.migrations); assertEquals(1, f.storage.current.data.version)
    }
    @Test fun staleMigrationDoesNotReplaceDataOrReuseConsent() = Fixture().use { f ->
        f.storage.current = Vault.create(); f.unlock()
        val consent = f.ui { requireNotNull(requestTransferMigration()) }
        f.storage.current = Vault.create().apply { addEntry("new", "synthetic", "AE") }
        f.ui { confirmTransferMigration(consent) }; f.idle()
        assertEquals(0, f.storage.migrations); assertEquals(1, f.storage.current.entries.size)
        f.ui { assertNull(activeVault); assertEquals(Screen.STORAGE_ERROR, currentScreen); assertFalse(confirmTransferMigration(consent)) }
    }
    @Test fun lockDuringStartedMigrationHidesUiAndPreservesLateNotice() = Fixture().use { f ->
        f.storage.current = Vault.create(); f.unlock()
        val consent = f.ui { requireNotNull(requestTransferMigration()) }; val gate = Gate()
        f.storage.beforeMigration = { gate.block() }
        f.ui { confirmTransferMigration(consent) }; gate.await()
        try { f.ui { lock(); assertNull(activeVault); assertNull(transferMigrationReview) } }
        finally { gate.release.countDown() }
        f.idle(); assertEquals(1, f.storage.migrations)
        f.ui { assertNull(activeVault); assertEquals(Screen.STORAGE_ERROR, currentScreen) }
        assertNotNull(f.storage.pendingNotice())
    }
    @Test fun migrationReviewBlocksManualWritesAndOwnsPrivateSnapshot() = Fixture().use { f ->
        f.storage.current = Vault.create().apply { addEntry("kept", "synthetic", "AE") }; f.unlock()
        val consent = f.ui { requireNotNull(requestTransferMigration()) }
        consent.baseline().entries.clear()
        assertEquals(1, consent.baseline().entries.size)
        f.ui {
            addAccount(); assertEquals(Screen.MAIN, currentScreen)
            submitAdditions(listOf(org.desktop2fa.desktop.watch.model.TotpEntry("manual", "synthetic", "AE")))
            assertFalse(operationPending); assertTrue(cancelTransferMigration(consent))
            addAccount(); assertEquals(Screen.ADD_ACCOUNT, currentScreen)
        }; assertEquals(0, f.storage.migrations)
    }
    @Test fun lockBeforeQueuedMigrationPreventsStorageEntry() = Fixture().use { f ->
        f.storage.current = Vault.create(); f.unlock()
        val consent = f.ui { requireNotNull(requestTransferMigration()) }; val gate = Gate()
        val occupied = CoroutineScope(f.io).launch { gate.block() }; gate.await()
        try { f.ui { confirmTransferMigration(consent); lock(); assertNull(transferMigrationReview) } }
        finally { gate.release.countDown() }
        runBlocking { occupied.join() }; f.idle()
        assertEquals(0, f.storage.migrations)
    }
    @Test fun statusIsAuthenticatedReadOnlyAndSurvivesNewManualSession() = Fixture().use { f ->
        f.unlock(); val batch = offer(); val (token, candidate) = f.prepare(batch)
        f.ui { approveTransfer(token, candidate) }; f.idle()
        val snapshot = f.storage.current.data.copy(entries = f.storage.current.entries.toMutableList())
        f.ui { assertTrue(queryTransferStatus(token, batch.transferId, batch.sha256)) }; f.idle()
        f.ui { assertEquals(candidate.receipt, (transferStatus as TransferStatusResult.Found).receipt) }
        f.ui { lock() }; f.idle(); f.unlock()
        val next = f.ui { requireNotNull(beginTransfer()) }
        f.ui { assertFalse(queryTransferStatus(token, batch.transferId, batch.sha256)); assertTrue(queryTransferStatus(next, batch.transferId, batch.sha256)) }; f.idle()
        f.ui { assertTrue(transferStatus is TransferStatusResult.Found) }
        assertEquals(snapshot, f.storage.current.data); assertEquals(1, f.storage.writes)
    }
    @Test fun unknownStatusDoesNotSaveOrReplacePendingApproval() = Fixture().use { f ->
        f.unlock(); val batch = offer(); val (token, candidate) = f.prepare(batch)
        f.ui { assertTrue(queryTransferStatus(token, batch.transferId, batch.sha256)) }; f.idle()
        f.ui { assertSame(TransferStatusResult.Unknown, transferStatus); assertSame(candidate, transferReview) }
        assertEquals(0, f.storage.writes); assertEquals(0, f.storage.migrations)
    }
    @Test fun statusDigestConflictIsFixedErrorAndDoesNotRewrite() = Fixture().use { f ->
        f.unlock(); val batch = offer(); val (token, candidate) = f.prepare(batch)
        f.ui { approveTransfer(token, candidate) }; f.idle()
        f.ui { queryTransferStatus(token, batch.transferId, "f".repeat(64)) }; f.idle()
        f.ui { assertEquals(TransferError.TX_CONFLICT, (transferStatus as TransferStatusResult.Failed).code) }
        assertEquals(1, f.storage.writes)
    }
    @Test fun statusCompletionAfterLockOrCancelCannotRepopulateSession() {
        for (lock in listOf(true, false)) Fixture().use { f ->
            f.unlock(); val token = f.ui { requireNotNull(beginTransfer()) }; val gate = Gate()
            f.storage.beforeStatus = { gate.block() }
            f.ui { queryTransferStatus(token, "1".repeat(32), "2".repeat(64)) }; gate.await()
            try { f.ui { if (lock) lock() else cancelTransfer(token); assertNull(transferStatus) } }
            finally { gate.release.countDown() }
            f.idle(); f.ui { assertNull(transferStatus); assertFalse(acceptsTransferToken(token)) }
        }
    }
    @Test fun failedStatusNeverFabricatesUnknown() = Fixture().use { f ->
        f.unlock(); val token = f.ui { requireNotNull(beginTransfer()) }
        f.storage.beforeStatus = { throw java.io.IOException("synthetic provider detail") }
        f.ui { queryTransferStatus(token, "1".repeat(32), "2".repeat(64)) }; f.idle()
        f.ui { assertEquals(TransferError.STORAGE_ERROR, (transferStatus as TransferStatusResult.Failed).code) }
        assertEquals(0, f.storage.writes)
    }
    companion object {
        private fun offer() = TransferOffer.parse(("""{"v":1,"type":"OFFER","transfer_id":"11111111111111111111111111111111","accounts":[{"item_id":"22222222222222222222222222222222","kind":"totp","issuer":"synthetic","account":"synthetic account","secret":"AA","algorithm":"SHA1","digits":6,"period":30}]}""").toByteArray())
    }
}
