package org.desktop2fa.desktop.watch.presentation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.migrationArchive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Device-only, private synthetic cache. No real vault, password, codes or screenshots. */
class MigrationRecoveryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun unlockErrorCanOpenExplicitPreMigrationCopyWithoutWritableSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-migration-ui-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        val isolated = SyntheticWatchContext(context, directory)
        val root = isolated.noBackupFilesDir
        val copy = migrationArchive(root)
        val archiveDirectory = requireNotNull(copy.parentFile) { "Synthetic archive requires a parent directory" }
        val main = File(directory, "vault.d2fa")
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        var primaryFailure: Throwable? = null
        try {
            assertTrue(archiveDirectory.mkdirs())
            val password = "Synthetic-Migration-Recovery-42!"
            val source = Vault.create().apply {
                addEntry("synthetic archive", "synthetic old account", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            }
            source.saveNew(copy, password)
            val original = copy.readBytes()
            main.writeText("synthetic unreadable current vault")
            val mainBytes = main.readBytes()
            val active = ActivityScenario.launch<SyntheticMainActivity>(
                Intent(context, SyntheticMainActivity::class.java).putExtra("syntheticDirectory", directory.name))
            scenario = active
            compose.waitUntil(40_000) {
                var ready = false
                active.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.currentScreen == Screen.UNLOCK } == true }
                ready
            }
            active.onActivity { activity ->
                val session = requireNotNull(activity.currentSession)
                activity.userAction(session, session.uiToken()) { session.passwordInput = password; session.unlock() }
            }
            compose.waitUntil(40_000) {
                var ready = false
                active.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.errorMessage != null } == true }
                ready
            }
            // This assertion is before decrypted accounts/codes appear; no code-tree diagnostics.
            compose.onNodeWithText("Review recovery files").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(40_000) {
                var ready = false
                active.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.currentScreen == Screen.RECOVERY } == true }
                ready
            }
            active.onActivity { activity ->
                val session = requireNotNull(activity.currentSession)
                val candidates = session.recoveryCandidates.filter { it.label.startsWith("Before transfer migration") }
                assertEquals("Exactly one synthetic migration source must be offered", 1, candidates.size)
                val candidate = candidates[0]
                activity.userAction(session, session.uiToken()) { session.passwordInput = password; session.openRecovery(candidate) }
            }
            compose.waitUntil(40_000) {
                var ready = false
                active.onActivity { ready = it.currentSession?.let { s -> !s.operationPending && s.currentScreen == Screen.MAIN } == true }
                ready
            }
            active.onActivity { activity ->
                val session = requireNotNull(activity.currentSession)
                assertNotNull(session.recoverySource)
                assertTrue(session.passwordInput.isEmpty())
                activity.userAction(session, session.uiToken()) { session.addAccount() }
                assertEquals(Screen.MAIN, session.currentScreen)
                activity.userAction(session, session.uiToken()) { session.lock() }
                assertNull(session.activeVault)
            }
            assertArrayEquals(mainBytes, main.readBytes()); assertArrayEquals(original, copy.readBytes())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                scenario?.close()
                // Never relax containment to clean up after a failed test.
                val owned = directory.canonicalFile
                check(owned.parentFile == context.cacheDir.canonicalFile && owned.name.startsWith("synthetic-migration-ui-"))
                listOf(copy, archiveDirectory, root, main).forEach { target ->
                    check(target.canonicalFile.toPath().startsWith(owned.toPath()))
                    target.delete()
                }
                directory.delete()
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
            }
        }
    }
}
