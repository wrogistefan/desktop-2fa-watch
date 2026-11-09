package org.desktop2fa.desktop.watch.presentation

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import org.desktop2fa.desktop.watch.vault.PasswordCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImmediateLockDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    // This Wear list version lacks ScrollToIndex semantics. Use bounded real gestures.
    private fun revealInWearList(matcher: SemanticsMatcher) {
        val positions = mutableListOf<String>()
        repeat(8) {
            // Geometry only; never put code text or credential values in diagnostics.
            positions.add(compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
                .joinToString { it.boundsInRoot.toString() })
            try {
                compose.onNode(matcher, useUnmergedTree = true).assertIsDisplayed()
                return
            } catch (_: AssertionError) {
                val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val x = bounds.center.x.toInt()
                // Keep gestures in the central content, away from bottom actions
                // and system edge gestures on a round Wear display.
                val start = (bounds.top + bounds.height * 0.60f).toInt()
                val end = (bounds.top + bounds.height * 0.30f).toInt()
                // Platform input uses real event timing for this Wear list version.
                ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("input swipe $x $start $x $end 500")).use { it.readBytes() }
                compose.waitForIdle()
            }
        }
        try {
            compose.onNode(matcher, useUnmergedTree = true).assertIsDisplayed()
        } catch (failure: AssertionError) {
            val markers = listOf("synthetic account", "Code unavailable", "No accounts", "Add Account", "Lock Vault", "Service name", "Unlock Vault", "Loading Secure Vault...")
                .map { label -> compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().size }
            throw AssertionError("Synthetic layout positions: $positions; marker counts=$markers; root=${compose.onRoot().fetchSemanticsNode().boundsInRoot}", failure)
        }
    }

    @Test
    fun unreadableSyntheticPreferencesShowManualUnlockWarningWithoutSetupOrReset() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        val context = SyntheticWatchContext(app, directory)
        val file = File(directory, "vault.d2fa")
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        try {
            Vault.create().saveNew(file, "synthetic unavailable password")
            val original = file.readBytes()
            assertTrue(context.getSharedPreferences(PasswordCache.SECURE_STORE, 0).edit()
                .putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "synthetic-invalid-keyset").commit())
            scenario = ActivityScenario.launch(Intent(app, SyntheticMainActivity::class.java)
                .putExtra("syntheticDirectory", directory.name))
            val warning = "Remembered password is unavailable. Unlock with your vault password; existing data has not been reset."
            compose.waitUntil(60_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Initialize Vault").assertCountEquals(0)
            assertArrayEquals(original, file.readBytes())
        } finally {
            scenario?.close()
            check(VaultSecurityManager.clearPassword(context))
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }

    @Test
    fun productionLockHidesCodesBeforeDelayedFailedCacheRemovalCompletes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        val preparedContext = SyntheticWatchContext(app, directory)
        val file = File(directory, "vault.d2fa")
        val password = "synthetic immediate lock password"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        var activityContext: SyntheticWatchContext? = null
        val code = SemanticsMatcher("synthetic six-digit code") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("[0-9]{6}")) } == true
        }
        try {
            // RFC 6238's public SHA1 key: the test concerns locking, not tiny-key providers.
            Vault.create().apply { addEntry("synthetic account", "synthetic service", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") }.saveNew(file, password)
            val original = file.readBytes()
            assertTrue(VaultSecurityManager.savePassword(preparedContext, password))
            scenario = ActivityScenario.launch(Intent(app, SyntheticMainActivity::class.java)
                .putExtra("syntheticDirectory", directory.name))
            // The splash also contains the app title. Wait for decrypted content,
            // not a title that could appear while the real KDF is still running.
            compose.waitUntil(60_000) {
                compose.onAllNodes(code, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(30_000) {
                var focused = false
                scenario.onActivity { focused = it.hasWindowFocus() }
                focused
            }
            revealInWearList(code)
            compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(1)
            scenario.onActivity { activity ->
                activityContext = activity.syntheticContext.apply {
                    refuseDeletion = true
                    deletionStarted = entered
                    deletionRelease = release
                }
            }
            revealInWearList(hasText("Lock Vault"))
            compose.onNodeWithText("Lock Vault").assertIsDisplayed().performClick()
            assertTrue("Cache clear reached the controlled boundary", entered.await(30, TimeUnit.SECONDS))
            compose.onNodeWithText("Removing remembered password…").performScrollTo().assertIsDisplayed()
            compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText("Unlock").performScrollTo().assertIsNotEnabled()
            assertArrayEquals(original, file.readBytes())
            release.countDown()
            val warning = "Could not confirm removal of the remembered password. It may still unlock the vault after restart."
            compose.waitUntil(60_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
            compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
            assertEquals(password, VaultSecurityManager.getPassword(preparedContext))
            assertArrayEquals(original, file.readBytes())
        } finally {
            release.countDown()
            scenario?.onActivity { activity -> activity.syntheticContext.refuseDeletion = false }
            activityContext?.refuseDeletion = false
            scenario?.close()
            check(VaultSecurityManager.clearPassword(preparedContext))
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }
}
