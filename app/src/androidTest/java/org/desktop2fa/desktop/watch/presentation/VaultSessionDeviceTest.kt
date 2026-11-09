package org.desktop2fa.desktop.watch.presentation

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VaultSessionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val code = SemanticsMatcher("synthetic code shape") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("[0-9]{6}")) } == true
    }
    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() { entered.countDown(); check(release.await(60, TimeUnit.SECONDS)) }
        fun await() = assertTrue("Synthetic I/O reached gate", entered.await(60, TimeUnit.SECONDS))
    }

    private fun waitClosed(scenario: ActivityScenario<SyntheticMainActivity>) {
        compose.waitUntil(60_000) {
            var ready = false
            scenario.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.currentScreen == Screen.UNLOCK } == true }
            ready
        }
        compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun unlock(scenario: ActivityScenario<SyntheticMainActivity>) {
        scenario.onActivity { it.currentSession!!.apply { passwordInput = PASSWORD; unlock() } }
        compose.waitUntil(60_000) { compose.onAllNodes(code, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun withVault(test: (ActivityScenario<SyntheticMainActivity>, File) -> Unit) {
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        val file = File(directory, "vault.d2fa")
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        try {
            Vault.create().apply { addEntry("synthetic old", "synthetic", KEY) }.saveNew(file, PASSWORD)
            // A historical remembered password must not bypass the new startup policy.
            assertTrue(VaultSecurityManager.savePassword(SyntheticWatchContext(app, directory), PASSWORD))
            scenario = ActivityScenario.launch(Intent(app, SyntheticMainActivity::class.java)
                .putExtra("syntheticDirectory", directory.name))
            waitClosed(scenario)
            compose.onAllNodesWithText("Remember on watch").assertCountEquals(0)
            test(scenario, file)
        } finally {
            scenario?.close()
            check(VaultSecurityManager.clearPassword(SyntheticWatchContext(app, directory)))
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }

    @Test fun lateUnlockAndActivityRecreationNeverRestoreAnInvalidatedSession() = withVault { scenario, file ->
        val original = file.readBytes()
        val gate = Gate()
        try {
            scenario.onActivity {
                it.afterIo = { name -> if (name == "unlock") gate.block() }
                it.currentSession!!.apply { passwordInput = PASSWORD; unlock() }
            }
            gate.await()
            compose.onNodeWithText("Lock Vault").performClick()
            scenario.onActivity {
                assertNull(it.currentSession!!.activeVault)
                assertEquals("", it.currentSession!!.passwordInput)
                assertEquals(AccountWizardState(), it.currentSession!!.wizardState)
            }
            compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
            scenario.recreate()
        } finally { gate.release.countDown() }
        waitClosed(scenario)
        scenario.onActivity { assertNull(it.currentSession!!.activeVault) }
        assertArrayEquals(original, file.readBytes())
        unlock(scenario)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun committedAdditionAfterLockStaysHiddenAndIsLoadedExactlyOnceAfterExplicitUnlock() = withVault { scenario, file ->
        unlock(scenario)
        val gate = Gate()
        try {
            scenario.onActivity {
                it.afterIo = { name -> if (name == "add") gate.block() }
                it.currentSession!!.apply {
                    addAccount()
                    wizardState = AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic new", KEY)
                    submitAccount()
                }
            }
            gate.await()
            compose.onNodeWithText("Lock Vault").performScrollTo().performClick()
            scenario.onActivity {
                assertNull(it.currentSession!!.activeVault)
                assertEquals(AccountWizardState(), it.currentSession!!.wizardState)
                assertEquals("", it.currentSession!!.passwordInput)
            }
            compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
        } finally { gate.release.countDown() }
        compose.waitUntil(60_000) {
            var ready = false
            scenario.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.currentScreen == Screen.STORAGE_ERROR } == true }
            ready
        }
        scenario.recreate() // A displayed late-Lock outcome is not yet acknowledged.
        compose.waitUntil(60_000) {
            var ready = false
            scenario.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.currentScreen == Screen.STORAGE_ERROR } == true }
            ready
        }
        scenario.onActivity {
            assertNull(it.currentSession!!.activeVault)
            assertEquals("Account was saved. Unlock and review before adding it again.", it.currentSession!!.errorMessage)
            it.currentSession!!.startup()
        }
        waitClosed(scenario)
        unlock(scenario)
        scenario.onActivity { assertEquals(2, it.currentSession!!.activeVault!!.entries.size) }
        val entries = Vault.load(file, PASSWORD).entries
        assertEquals(listOf("synthetic old", "synthetic new"), entries.map { it.accountName })
    }

    @Test fun recreationDropsTheActiveVaultAndSecretDraftWithoutChangingCommittedBytes() = withVault { scenario, file ->
        val original = file.readBytes()
        unlock(scenario)
        scenario.onActivity {
            it.currentSession!!.apply { addAccount(); wizardState = AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic draft", KEY) }
        }
        scenario.recreate()
        waitClosed(scenario)
        scenario.onActivity {
            assertNull(it.currentSession!!.activeVault)
            assertEquals(AccountWizardState(), it.currentSession!!.wizardState)
            assertEquals("", it.currentSession!!.passwordInput)
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun committedWriteAfterDisposalHandsReviewToRecreatedOwnersUntilExplicitRetry() = withVault { scenario, file ->
        unlock(scenario)
        val gate = Gate()
        try {
            scenario.onActivity {
                it.afterIo = { name -> if (name == "add") gate.block() }
                it.currentSession!!.apply {
                    addAccount()
                    wizardState = AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic new", KEY)
                    submitAccount()
                }
            }
            gate.await()
            scenario.recreate()
        } finally { gate.release.countDown() }
        fun waitReview() {
            compose.waitUntil(60_000) {
                var ready = false
                scenario.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.currentScreen == Screen.STORAGE_ERROR } == true }
                ready
            }
            compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
            scenario.onActivity {
                assertNull(it.currentSession!!.activeVault)
                assertEquals("Account was saved. Unlock and review before adding it again.", it.currentSession!!.errorMessage)
            }
        }
        waitReview()
        scenario.recreate()
        waitReview() // Merely displaying/disposing a notice must not consume it.
        scenario.onActivity { it.currentSession!!.startup() }
        waitClosed(scenario)
        unlock(scenario)
        assertEquals(2, Vault.load(file, PASSWORD).entries.size)
    }

    companion object {
        private const val PASSWORD = "Synthetic-DeviceSession-42!"
        private const val KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}
