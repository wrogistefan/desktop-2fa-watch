package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.transfer.*
import org.desktop2fa.desktop.watch.vault.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VaultTransferOwnerTest {
    private val sid = "a".repeat(32)
    private val batch = TransferOffer.parse(OFFER.toByteArray())
    private class Gate {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fun block() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        fun await() { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
    }
    private class Storage : VaultSessionStorage(File("synthetic-owner-${System.nanoTime()}.d2fa"), { true }) {
        @Volatile var current = Vault(VaultData(2))
        @Volatile var beforeSave: () -> Unit = {}
        @Volatile var atSave: () -> Unit = {}
        @Volatile var statusReads = 0
        @Volatile var writes = 0
        override fun unlock(password: String) = current
        override fun prepareTransfer(offer: TransferOffer, password: String) = TransferPlanner.prepare(current.data, offer)
        override fun transferStatus(transferId: String, sha256: String, password: String): org.desktop2fa.desktop.watch.model.TransferReceipt? {
            statusReads++; return TransferPlanner.status(current.data, transferId, sha256)
        }
        override fun commitTransfer(candidate: TransferAdditionCandidate, password: String, approval: TransferSaveGate): TransferCommitResult {
            beforeSave()
            return commitApprovedTransfer(candidate, File("synthetic-unused"), password, approval, object : TransferCommitIO() {
                override fun load(file: File, password: String) = current
                override fun inspect(file: File) = VaultPresence.PRESENT
                override fun mainPresent(file: File) = true
                override fun save(vault: Vault, file: File, password: String): VaultSaveOutcome {
                    atSave(); current = vault; writes++; return VaultSaveOutcome.COMMITTED
                }
            })
        }
    }
    private class Fixture : AutoCloseable {
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val job = SupervisorJob(); val scope = CoroutineScope(job + ui)
        val storage = Storage(); val session = VaultSession(scope, storage, io)
        var time = 0L
        var owner: VaultTransferOwner? = null
        fun <T> ui(action: () -> T): T = runBlocking { withContext(ui) { action() } }
        fun idle() = runBlocking { withTimeout(5_000) { while (ui { session.operationPending }) delay(1) } }
        fun open(): VaultTransferOwner {
            ui { session.passwordInput = "Synthetic-Owner-42!"; session.unlock() }; idle()
            return ui {
                requireNotNull(VaultTransferOwner.open(session, scope, "a".repeat(32), 900_000, { time }) {
                    if (time >= 60_000) { session.lock(); false } else true
                }).also { owner = it }
            }
        }
        fun phase(phase: TransferProtocol.Phase) = runBlocking {
            withTimeout(5_000) { while (ui { owner!!.phase != phase }) delay(1) }
        }
        fun next(): TransferEffect = runBlocking { withTimeout(5_000) { requireNotNull(owner!!.nextOutput()) } }
        fun message(): JsonObject = Json.parseToJsonElement((next() as TransferEffect.Send).payload().decodeToString()).jsonObject
        fun type() = message()["type"]!!.jsonPrimitive.content
        fun ready() {
            ui { owner!!.authenticated() }; assertEquals("HELLO", type())
            ui { owner!!.receive("""{"v":1,"type":"READY","session_id":"${"a".repeat(32)}","max_accounts":50,"max_frame":262144}""".toByteArray()) }
        }
        fun review(): TransferAdditionCandidate {
            ready(); ui { owner!!.receive(OFFER.toByteArray()) }; phase(TransferProtocol.Phase.REVIEW)
            assertEquals("REVIEW", type()); return ui { requireNotNull(owner!!.review) }
        }
        fun status(batch: TransferOffer) {
            ui { owner!!.receive("""{"v":1,"type":"STATUS","transfer_id":"${batch.transferId}","offer_sha256":"${batch.sha256}"}""".toByteArray()) }
        }
        override fun close() {
            ui { owner?.transportClosed(); session.dispose() }
            runBlocking { withTimeout(5_000) { job.cancelAndJoin() } }; io.close(); ui.close()
        }
    }
    @Test fun completeSessionPublishesOnlyAfterLocalApprovalAndEveryStatusReloads() = Fixture().use { f ->
        val owner = f.open(); val candidate = f.review(); assertEquals(0, f.storage.writes)
        f.ui { owner.approve(candidate) }; f.phase(TransferProtocol.Phase.SAVED)
        assertEquals("RESULT", f.type()); assertEquals(1, f.storage.writes)
        repeat(2) { f.status(batch); f.phase(TransferProtocol.Phase.SAVED); assertEquals("RESULT", f.type()) }
        assertEquals(2, f.storage.statusReads); assertEquals(1, f.storage.writes)
    }
    @Test fun blockedWriterDoesNotBlockHeartbeatOrTooLateCancel() = Fixture().use { f ->
        val owner = f.open(); val candidate = f.review(); val gate = Gate(); f.storage.atSave = { gate.block() }
        f.ui { owner.approve(candidate) }; gate.await()
        try {
            f.ui { owner.receive(TransferWire.ping()) }; assertEquals("PONG", f.type())
            f.ui { owner.cancel() }; assertEquals("TOO_LATE", f.message()["code"]!!.jsonPrimitive.content)
        } finally { gate.release.countDown() }
        f.phase(TransferProtocol.Phase.SAVED); assertEquals("RESULT", f.type()); assertEquals(1, f.storage.writes)
    }
    @Test fun lockBeforeWriterBoundaryDropsUiAndPreventsSave() = Fixture().use { f ->
        val owner = f.open(); val candidate = f.review(); val gate = Gate(); f.storage.beforeSave = { gate.block() }
        f.ui { owner.approve(candidate) }; gate.await()
        try {
            f.ui { f.session.lock(); assertNull(owner.review); assertNull(f.session.activeVault) }
            f.phase(TransferProtocol.Phase.CLOSED)
            assertEquals("VAULT_LOCKED", f.message()["code"]!!.jsonPrimitive.content)
        } finally { gate.release.countDown() }
        f.idle(); assertEquals(0, f.storage.writes)
    }
    @Test fun disconnectedReviewRequiresStatusAndFreshConsent() = Fixture().use { f ->
        val owner = f.open(); val old = f.review()
        f.ui { owner.disconnected() }; assertSame(TransferEffect.CloseChannel, f.next())
        f.ready(); f.status(batch); f.phase(TransferProtocol.Phase.IDLE); assertEquals("UNKNOWN", f.type())
        f.ui { owner.receive(OFFER.toByteArray()) }; f.phase(TransferProtocol.Phase.REVIEW); assertEquals("REVIEW", f.type())
        val fresh = f.ui { requireNotNull(owner.review) }; assertNotSame(old, fresh)
        f.ui { owner.approve(old) }; assertEquals(0, f.storage.writes)
        f.ui { owner.approve(fresh) }; f.phase(TransferProtocol.Phase.SAVED); assertEquals("RESULT", f.type())
    }
    @Test fun replacementChannelAuthenticatesImmediatelyAndStatusWaitsForOldWriter() = Fixture().use { f ->
        val owner = f.open(); val candidate = f.review(); val gate = Gate(); f.storage.atSave = { gate.block() }
        f.ui { owner.approve(candidate) }; gate.await()
        try {
            f.ui { owner.disconnected() }; assertSame(TransferEffect.CloseChannel, f.next())
            f.ready(); f.status(batch)
            assertEquals(TransferProtocol.Phase.LOOKUP, f.ui { owner.phase })
            assertEquals(0, f.storage.statusReads)
            f.ui { owner.receive(TransferWire.ping()) }; assertEquals("PONG", f.type())
        } finally { gate.release.countDown() }
        f.phase(TransferProtocol.Phase.SAVED); assertEquals("RESULT", f.type())
        assertEquals(1, f.storage.writes); assertEquals(1, f.storage.statusReads)
    }
    @Test fun heartbeatCannotRenewLocalInactivityAndLeaseNeedsClosureAcknowledgement() = Fixture().use { f ->
        val owner = f.open(); f.review()
        for (time in 10_000L..50_000L step 10_000L) {
            f.ui { f.time = time; owner.receive(TransferWire.ping()) }; assertEquals("PONG", f.type())
        }
        f.ui { f.time = 59_999; owner.receive(TransferWire.ping()) }; assertEquals("PONG", f.type())
        f.ui { f.time = 60_000; owner.tick() }; f.phase(TransferProtocol.Phase.CLOSED)
        assertNull(f.ui { f.session.activeVault })
        f.ui { assertNull(VaultTransferOwner.open(f.session, f.scope, sid, 900_000, { 0 }, { true })) }
    }
    @Test fun changedStorageProducesNewReviewAndRejectsOldConsent() = Fixture().use { f ->
        val owner = f.open(); val old = f.review()
        f.storage.current = Vault(VaultData(2)).apply { addEntry("other", "synthetic", "AE") }
        f.ui { owner.approve(old) }; f.phase(TransferProtocol.Phase.REVIEW)
        assertEquals("REVIEW", f.type()); assertEquals(0, f.storage.writes)
        val fresh = f.ui { requireNotNull(owner.review) }; assertNotSame(old, fresh)
        f.ui { owner.approve(old) }; assertEquals(0, f.storage.writes)
        f.ui { owner.approve(fresh) }; f.phase(TransferProtocol.Phase.SAVED)
        assertEquals("RESULT", f.type()); assertEquals(2, f.storage.current.entries.size)
    }
    @Test fun cancellingCompositionScopeClosesTheProtocolAndOutput() = Fixture().use { f ->
        val owner = f.open(); f.ready()
        f.ui { f.session.dispose(); f.job.cancel() }
        runBlocking { withTimeout(5_000) { f.job.join() } }
        assertEquals(TransferProtocol.Phase.CLOSED, f.ui { owner.phase })
        assertEquals("VAULT_LOCKED", f.message()["code"]!!.jsonPrimitive.content)
        assertSame(TransferEffect.CloseChannel, f.next()); assertSame(TransferEffect.EndSession, f.next())
        assertNull(runBlocking { withTimeout(5_000) { owner.nextOutput() } })
    }
    @Test fun newActivityCannotAcquireLeaseUntilOldTransportAcknowledgesClosure() = Fixture().use { f ->
        val old = f.open(); f.ready(); f.ui { old.close() }
        val next = VaultSession(f.scope, Storage(), f.io)
        try {
            f.ui { next.passwordInput = "Synthetic-Next-42!"; next.unlock() }
            runBlocking { withTimeout(5_000) { while (f.ui { next.operationPending }) delay(1) } }
            f.ui {
                assertNull(VaultTransferOwner.open(next, f.scope, sid, 900_000, { 0 }, { true }))
                old.transportClosed()
                val replacement = requireNotNull(VaultTransferOwner.open(next, f.scope, sid, 900_000, { 0 }, { true }))
                replacement.transportClosed()
            }
        } finally { f.ui { next.dispose() } }
    }
    @Test fun invalidConstructionOrCancelledScopeCannotConsumeTheSessionDraft() = Fixture().use { f ->
        f.ui { f.session.passwordInput = "Synthetic-Owner-42!"; f.session.unlock() }; f.idle()
        f.ui {
            try { VaultTransferOwner.open(f.session, f.scope, "invalid", 900_000, { 0 }, { true }); fail() }
            catch (_: IllegalArgumentException) {}
            val stopped = CoroutineScope(Job().apply { cancel() } + f.ui)
            assertNull(VaultTransferOwner.open(f.session, stopped, sid, 900_000, { 0 }, { true }))
            val owner = requireNotNull(VaultTransferOwner.open(f.session, f.scope, sid, 900_000, { 0 }, { true }))
            f.owner = owner; owner.transportClosed()
        }
    }
    @Test fun boundedOutboxEndsUnconsumedPeerInsteadOfAccumulatingWork() = Fixture().use { f ->
        val owner = f.open(); f.ready()
        f.ui { repeat(17) { owner.receive(TransferWire.ping()) } }
        assertEquals(TransferProtocol.Phase.CLOSED, f.ui { owner.phase })
        assertEquals("BUSY", f.message()["code"]!!.jsonPrimitive.content)
        assertSame(TransferEffect.CloseChannel, f.next()); assertSame(TransferEffect.EndSession, f.next())
    }
    companion object {
        private const val OFFER = """{"v":1,"type":"OFFER","transfer_id":"11111111111111111111111111111111","accounts":[{"item_id":"22222222222222222222222222222222","kind":"totp","issuer":"synthetic","account":"synthetic account","secret":"AA","algorithm":"SHA1","digits":6,"period":30}]}"""
    }
}
