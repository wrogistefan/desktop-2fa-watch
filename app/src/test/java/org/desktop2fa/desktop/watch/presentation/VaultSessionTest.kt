package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.*
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.vault.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VaultSessionTest {
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Synthetic I/O gate timed out" }
        }
        fun await() = assertTrue("Synthetic I/O reached gate", entered.await(5, TimeUnit.SECONDS))
    }

    private class Storage : VaultSessionStorage(File("synthetic-unused.d2fa"), { true }) {
        @Volatile var before: (String) -> Unit = {}
        @Volatile var after: (String) -> Unit = {}
        @Volatile var current = Vault.create().apply { addEntry("synthetic old", "synthetic", KEY) }
        @Volatile var missing = false
        @Volatile var cleanupSucceeds = true
        @Volatile var calls = 0
        @Volatile var cleanups = 0
        private fun <T> work(name: String, body: () -> T): T {
            before(name)
            calls++
            return body().also { after(name) }
        }
        override fun clearCredentials(): Boolean { cleanups++; return cleanupSucceeds }
        override fun inspect() = work("startup") { VaultStartup(if (missing) Screen.SETUP else Screen.UNLOCK) }
        override fun unlock(password: String) = work("unlock") {
            if (password != PASSWORD) throw InvalidPasswordException("synthetic provider detail")
            current
        }
        override fun create(password: String) = work("setup") { Vault.create().also { current = it; missing = false } }
        override fun inventory() = work("inventory") { listOf(CANDIDATE) }
        override fun recover(candidate: RecoveryCandidate, password: String) = work("recovery") { RecoveryView(current, "Synthetic backup") }
        override fun add(snapshot: VaultData, draft: AccountWizardState, password: String) = work("add") {
            assertEquals(PASSWORD, password)
            Vault(snapshot).also { it.addEntry(draft.account, draft.issuer, draft.secret); current = it }
                .let { AccountSaveResult(VaultSaveOutcome.COMMITTED, it) }
        }
    }

    private class Fixture(val storage: Storage = Storage()) : AutoCloseable {
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newSingleThreadExecutor()
        val io = ioExecutor.asCoroutineDispatcher()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + ui)
        val session = VaultSession(scope, storage, io)
        fun <T> ui(action: VaultSession.() -> T): T = runBlocking { withContext(ui) { session.action() } }
        fun idle() = runBlocking {
            withTimeout(5_000) { while (ui { operationPending }) delay(1) }
        }
        fun unlock() { ui { passwordInput = PASSWORD; unlock() }; idle() }
        override fun close() {
            ui { dispose() }
            runBlocking { withTimeout(5_000) { job.cancelAndJoin() } }
            io.close()
            ui.close()
        }
    }

    private fun VaultSession.assertClosed() {
        assertNull(activeVault)
        assertEquals("", passwordInput)
        assertEquals(AccountWizardState(), wizardState)
        assertNull(recoverySource)
        assertTrue(recoveryCandidates.isEmpty())
        assertNull(addedAccountIndex)
        assertNull(successMessage)
        assertNotEquals(Screen.MAIN, currentScreen)
        assertNotEquals(Screen.ADD_ACCOUNT, currentScreen)
    }

    private fun Fixture.prepare(kind: String) {
        if (kind == "add") { unlock(); ui { addAccount(); wizardState = DRAFT } }
        if (kind == "setup") storage.missing = true
    }

    private fun VaultSession.start(kind: String) {
        when (kind) {
            "startup" -> startup()
            "unlock" -> { passwordInput = PASSWORD; unlock() }
            "setup" -> { passwordInput = PASSWORD; setup() }
            "inventory" -> listRecovery()
            "recovery" -> { passwordInput = PASSWORD; openRecovery(CANDIDATE) }
            "add" -> submitAccount()
        }
    }

    @Test fun normalPasswordSessionAndAdditionKeepAnImmutableExistingSnapshot() = Fixture().use { f ->
        f.ui { startup() }; f.idle()
        f.ui { assertEquals(Screen.UNLOCK, currentScreen); assertClosed() }
        f.unlock()
        val original = f.ui { requireNotNull(activeVault) }
        f.ui { addAccount(); wizardState = DRAFT; submitAccount() }; f.idle()
        f.ui {
            assertEquals(Screen.MAIN, currentScreen)
            assertEquals(2, activeVault!!.entries.size)
            assertEquals("", passwordInput)
            assertEquals(AccountWizardState(), wizardState)
        }
        assertEquals(1, original.entries.size)
        assertEquals(2, f.storage.current.entries.size)
    }

    @Test fun allLateSuccessesStayClosedAndClearDraftsBeforeIoReturns() {
        for (kind in listOf("startup", "unlock", "setup", "inventory", "recovery", "add")) {
            Fixture().use { f ->
                f.prepare(kind)
                val gate = Gate()
                f.storage.after = { if (it == kind) gate.block() }
                try {
                    f.ui { start(kind) }; gate.await()
                    val calls = f.storage.calls
                    f.ui {
                        lock(); assertClosed()
                        assertTrue(operationPending)
                        passwordInput = PASSWORD; unlock(); setup()
                        passwordInput = ""
                    }
                    assertEquals(calls, f.storage.calls)
                } finally { gate.release.countDown() }
                f.idle()
                f.ui { assertClosed(); assertFalse(operationPending) }
                if (kind in listOf("setup", "add")) f.ui { assertEquals(Screen.STORAGE_ERROR, currentScreen) }
                if (kind == "add") {
                    assertEquals(2, f.storage.current.entries.size)
                    f.unlock()
                    f.ui { assertEquals(2, activeVault!!.entries.size) }
                }
            }
        }
    }

    @Test fun errorsFromOldOperationsCannotReplaceLockedStateOrExposeDiagnostics() {
        for (kind in listOf("startup", "unlock", "setup", "inventory", "recovery", "add")) {
            Fixture().use { f ->
                f.prepare(kind)
                val gate = Gate()
                f.storage.before = { if (it == kind) { gate.block(); error("synthetic provider detail") } }
                try { f.ui { start(kind) }; gate.await(); f.ui { lock(); assertClosed() } }
                finally { gate.release.countDown() }
                f.idle()
                f.ui { assertClosed(); assertFalse(errorMessage.orEmpty().contains("synthetic provider detail")) }
                assertEquals(1, f.storage.current.entries.size)
            }
        }
    }

    @Test fun lockBeforeQueuedMutationStartsPreventsAnyWrite() = Fixture().use { f ->
        f.unlock()
        val occupied = Gate()
        f.ioExecutor.submit { occupied.block() }
        occupied.await()
        val calls = f.storage.calls
        try {
            f.ui { addAccount(); wizardState = DRAFT; submitAccount(); lock(); assertClosed() }
        } finally { occupied.release.countDown() }
        f.idle()
        assertEquals(calls, f.storage.calls)
        assertEquals(1, f.storage.current.entries.size)
    }

    @Test fun disposalAndCancellationAfterCommitPreserveDataButNeverRestoreOldSession() {
        for (kind in listOf("setup", "add")) {
            Fixture().use { f ->
                f.prepare(kind)
                val gate = Gate()
                f.storage.after = { if (it == kind) gate.block() }
                try {
                    f.ui { start(kind) }; gate.await()
                    f.ui { dispose(); assertClosed() }
                    f.job.cancel()
                } finally { gate.release.countDown() }
                runBlocking { withTimeout(5_000) { f.job.join() } }
                f.ui { assertClosed() }
                // A new process/owner has no session or draft and must authenticate again.
                Fixture(f.storage).use { restarted ->
                    restarted.ui { startup() }; restarted.idle()
                    restarted.ui { assertClosed(); assertEquals(Screen.UNLOCK, currentScreen) }
                    restarted.unlock()
                    restarted.ui { assertEquals(if (kind == "add") 2 else 0, activeVault!!.entries.size) }
                }
            }
        }
    }

    @Test fun cancellationBeforeDispatchDoesNotWriteOrPublishAnActiveSession() = Fixture().use { f ->
        f.storage.missing = true
        val occupied = Gate()
        f.ioExecutor.submit { occupied.block() }; occupied.await()
        try {
            f.ui { passwordInput = PASSWORD; setup(); dispose() }
            f.job.cancel()
        } finally { occupied.release.countDown() }
        runBlocking { withTimeout(5_000) { f.job.join() } }
        assertEquals(0, f.storage.calls)
        f.ui { assertClosed() }
    }

    @Test fun failedLegacyCleanupCannotOpenOrOverwriteAVaultAndWarningSurvivesUnlock() = Fixture().use { f ->
        f.storage.cleanupSucceeds = false
        f.ui { startup() }; f.idle()
        f.ui { assertClosed(); assertEquals(credentialCleanupWarning(false), cacheWarning) }
        assertEquals(1, f.storage.current.entries.size)
        f.unlock()
        f.ui { assertEquals(Screen.MAIN, currentScreen); assertNotNull(cacheWarning); lock(); assertClosed() }
        f.idle()
        f.ui { assertClosed(); assertNotNull(cacheWarning) }
        assertEquals(1, f.storage.current.entries.size)
    }

    @Test fun recreatedOwnerWaitsForOldWriteAndThenRequiresANewPassword() = Fixture().use { old ->
        old.unlock()
        val gate = Gate()
        old.storage.after = { if (it == "add") gate.block() }
        old.ui { addAccount(); wizardState = DRAFT; submitAccount() }; gate.await()
        try {
            old.ui { dispose(); assertClosed() }
            old.job.cancel()
            Fixture(old.storage).use { replacement ->
                replacement.ui {
                    startup()
                    assertTrue(operationPending)
                    assertNull(activeVault)
                }
                gate.release.countDown()
                replacement.idle()
                replacement.ui { assertEquals(Screen.UNLOCK, currentScreen); assertClosed() }
                replacement.unlock()
                replacement.ui { assertEquals(2, activeVault!!.entries.size) }
            }
        } finally { gate.release.countDown() }
    }

    @Test fun wrongPasswordAndReadCancellationClearInputsWithoutASetupFallback() = Fixture().use { f ->
        f.ui { startup() }; f.idle()
        f.ui { passwordInput = "synthetic wrong password"; unlock() }; f.idle()
        f.ui { assertClosed(); assertEquals(Screen.UNLOCK, currentScreen); assertEquals("Invalid password", errorMessage) }
        val gate = Gate()
        f.storage.after = { if (it == "unlock") gate.block() }
        try { f.ui { passwordInput = PASSWORD; unlock() }; gate.await(); f.job.cancel() }
        finally { gate.release.countDown() }
        runBlocking { withTimeout(5_000) { f.job.join() } }
        f.ui { assertClosed(); assertEquals(Screen.STORAGE_ERROR, currentScreen) }
    }

    companion object {
        private const val PASSWORD = "Synthetic-Session-Password-42!"
        private const val KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        private val CANDIDATE = RecoveryCandidate(File("synthetic-unused-backup"), "Synthetic backup")
        private val DRAFT = AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic new", KEY)
    }
}
