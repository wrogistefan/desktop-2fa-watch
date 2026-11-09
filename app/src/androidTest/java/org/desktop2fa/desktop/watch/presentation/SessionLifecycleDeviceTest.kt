package org.desktop2fa.desktop.watch.presentation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.PowerManager
import android.view.View
import android.inputmethodservice.ExtractEditText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticFullscreenIme
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.TotpGenerator
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionLifecycleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val code = SemanticsMatcher("synthetic code shape") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("[0-9]{6}")) } == true
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    // UiAutomation tokenizes commands directly; shell quote characters become
    // literal arguments. Only allow platform identifiers/settings as tokens.
    private fun quote(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_./:-]+")))
        return value
    }

    private fun await(condition: () -> Boolean) {
        // A stopped Activity cannot service Compose frames. Poll lifecycle/I/O
        // independently; only active editor assertions advance the test clock.
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Synthetic lifecycle condition completed", condition())
    }
    private fun state(scenario: ActivityScenario<SyntheticMainActivity>, condition: (SyntheticMainActivity, VaultSession) -> Boolean): Boolean {
        var result = false
        scenario.onActivity { activity -> result = activity.currentSession?.let { condition(activity, it) } ?: false }
        return result
    }
    private fun closed(scenario: ActivityScenario<SyntheticMainActivity>) {
        await { state(scenario) { _, s -> !s.operationPending && s.activeVault == null && s.currentScreen == Screen.UNLOCK } }
        compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
    }
    private fun action(scenario: ActivityScenario<SyntheticMainActivity>, body: VaultSession.() -> Unit) {
        scenario.onActivity { activity ->
            val session = requireNotNull(activity.currentSession)
            activity.userAction(session, session.uiToken()) { session.body() }
        }
    }
    private fun unlock(scenario: ActivityScenario<SyntheticMainActivity>) {
        action(scenario) { passwordInput = PASSWORD; unlock() }
        await { state(scenario) { a, s -> !s.operationPending && s.activeVault != null && a.codesActive } }
        compose.waitForIdle()
    }
    private fun withVault(test: (ActivityScenario<SyntheticMainActivity>, File) -> Unit) {
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        try {
            val file = File(directory, "vault.d2fa")
            Vault.create().apply { addEntry("synthetic old", "synthetic", KEY) }.saveNew(file, PASSWORD)
            scenario = ActivityScenario.launch(Intent(app, SyntheticMainActivity::class.java)
                .putExtra("syntheticDirectory", directory.name))
            closed(scenario)
            test(scenario, file)
        } finally {
            scenario?.close()
            check(VaultSecurityManager.clearPassword(SyntheticWatchContext(app, directory)))
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }

    @Test fun exactDeadlineClearsDraftAndRejectsALateEditorCallback() = withVault { scenario, file ->
        val bytes = file.readBytes()
        unlock(scenario)
        var token: Any? = null
        var start = 0L
        scenario.onActivity { a ->
            start = SystemClock.elapsedRealtime() + 1_000
            a.monotonicOffset = 0
            a.monotonicOverride = start
            val s = requireNotNull(a.currentSession)
            a.userAction(s, s.uiToken()) { s.addAccount(); s.wizardState = AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic draft", KEY) }
            token = s.uiToken()
            a.monotonicOverride = start + 59_999
            assertTrue(a.checkSessionDeadline())
            assertNotNull(s.activeVault)
            a.monotonicOverride = start + 60_000
            assertFalse(a.checkSessionDeadline())
            a.userAction(s, requireNotNull(token)) { s.wizardState = AccountWizardState(secret = KEY) }
            assertEquals(AccountWizardState(), s.wizardState)
            assertEquals("", s.passwordInput)
            assertNull(s.activeVault)
        }
        closed(scenario)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun pauseHidesCodesWithoutDiscardingButStopRequiresANewPassword() = withVault { scenario, file ->
        val bytes = file.readBytes()
        unlock(scenario)
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity { a -> assertFalse(a.codesActive); assertNotNull(a.currentSession!!.activeVault) }
        compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await { state(scenario) { a, s -> a.codesActive && s.activeVault != null } }
        scenario.moveToState(Lifecycle.State.CREATED)
        await { state(scenario) { _, s -> !s.operationPending && s.activeVault == null } }
        scenario.moveToState(Lifecycle.State.RESUMED)
        closed(scenario)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun screenOffFinishesCleanupAndWakingDoesNotReopenCodes() = withVault { scenario, file ->
        val bytes = file.readBytes()
        unlock(scenario)
        try {
            shell("input keyevent 223")
            await { state(scenario) { a, s -> !a.codesActive && s.activeVault == null && !s.operationPending } }
        } finally { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
        scenario.moveToState(Lifecycle.State.RESUMED)
        closed(scenario)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun secureEmulatorLockNeverAuthorizesCacheOrAResumedSession() {
        assumeTrue("Only disposable emulator credentials may be changed", shell("getprop ro.kernel.qemu") == "1")
        assumeTrue("Never replace a pre-existing device credential", !app.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val disabled = shell("locksettings get-disabled")
        assumeTrue(disabled in setOf("true", "false"))
        var installed = false
        try {
            withVault { scenario, file ->
                unlock(scenario)
                val bytes = file.readBytes()
                // Establish the active session before enabling Keyguard: enabling
                // it may immediately lock this emulator, which is the event under test.
                check(!shell("locksettings set-pin 246810").contains("Error", ignoreCase = true))
                installed = true
                shell("locksettings set-disabled false")
                assertTrue(app.getSystemService(KeyguardManager::class.java).isDeviceSecure)
                assertTrue("OS lock-screen action accepted", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
                // isKeyguardLocked can already be true for an insecure,
                // occluded Wear screen. Wait for the actual authentication lock
                // and sleep before waking, otherwise wake can race lockNow.
                await { app.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
                    !app.getSystemService(PowerManager::class.java).isInteractive }
                await { state(scenario) { _, s -> s.activeVault == null } }
                shell("input keyevent 224")
                await { state(scenario) { a, s -> !a.codesActive && s.activeVault == null } }
                shell("locksettings clear --old 246810")
                await { !app.getSystemService(KeyguardManager::class.java).isDeviceSecure }
                installed = false
                shell("locksettings set-disabled $disabled")
                shell("wm dismiss-keyguard")
                scenario.moveToState(Lifecycle.State.RESUMED)
                closed(scenario)
                assertArrayEquals(bytes, file.readBytes())
            }
        } finally {
            if (installed) shell("locksettings clear --old 246810")
            shell("locksettings set-disabled $disabled")
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
        }
    }

    @Test fun wallClockChangeWhilePausedIsAppliedOnResumeWithoutExtendingIdle() = withVault { scenario, file ->
        unlock(scenario)
        val bytes = file.readBytes()
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.onActivity { a -> a.wallClockOverride = 1_111_111_109_000; a.clockChangedForTest() }
        compose.onAllNodes(code, useUnmergedTree = true).assertCountEquals(0)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await { state(scenario) { a, s -> a.codesActive && s.activeVault != null } }
        val expected = TotpGenerator.generateTotp(KEY, timeSeconds = 1_111_111_109)
        compose.waitUntil(10_000) {
            compose.onAllNodes(code, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == expected } == true
            }
        }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun nativeFullscreenExtractEditorKeepsDraftAndDeliversNextAndDone() = withVault { scenario, file ->
        fun awaitNative(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 60_000) { condition() }
        val ime = "${app.packageName}/.testing.SyntheticFullscreenIme"
        val original = shell("settings get secure default_input_method")
        val showWithHardware = shell("settings get secure show_ime_with_hard_keyboard")
        require(showWithHardware in setOf("null", "0", "1"))
        val wasEnabled = shell("settings get secure enabled_input_methods").split(':').any { it.substringBefore(';') == ime }
        fun nativeView(name: String): View? {
            var found: View? = null
            instrumentation.runOnMainSync {
                val root = SyntheticFullscreenIme.extractView
                val id = root?.resources?.getIdentifier(name, "id", "android") ?: 0
                if (root?.isShown == true && id != 0) {
                    found = root.findViewById<View>(id)?.takeIf { it.isShown && it.isEnabled }
                }
            }
            return found
        }
        try {
            shell("ime enable ${quote(ime)}"); shell("ime set ${quote(ime)}")
            assertEquals("Synthetic IME selected by Android", ime, shell("settings get secure default_input_method"))
            shell("settings put secure show_ime_with_hard_keyboard 1")
            unlock(scenario)
            action(scenario) { addAccount() }
            scenario.onActivity { it.monotonicOverride = SystemClock.elapsedRealtime() + 1_000 }
            action(scenario) {} // Begin the controlled interval with qualifying input.
            fun nativeField(value: String) {
                compose.onNode(hasSetTextAction()).performClick()
                awaitNative { SyntheticFullscreenIme.active && SyntheticFullscreenIme.fullscreen }
                scenario.onActivity {
                    it.monotonicOverride = requireNotNull(it.monotonicOverride) + 59_000
                    assertTrue("Draft remains inside its pre-edit deadline", it.checkSessionDeadline())
                    assertNotNull(it.currentSession!!.activeVault)
                }
                var editor: ExtractEditText? = null
                awaitNative {
                    editor = nativeView("inputExtractEditText") as? ExtractEditText
                    editor != null
                }
                // ExtractEditText reports itself focused; ACTION_FOCUS can return
                // false because no focus change is needed.
                assertNotNull("Android's actual native ExtractEditText is shown", editor)
                instrumentation.sendStringSync(value)
                try {
                    compose.waitUntil(timeoutMillis = 60_000) { state(scenario) { _, s -> s.wizardState.value == value } }
                } catch (failure: Exception) {
                    scenario.onActivity { a ->
                        val s = requireNotNull(a.currentSession)
                        fail("Synthetic edit not delivered: screen=${s.currentScreen}, step=${s.wizardState.step}, " +
                            "length=${s.wizardState.value.length}, active=${s.activeVault != null}")
                    }
                    throw failure
                }
                scenario.onActivity {
                    it.monotonicOverride = requireNotNull(it.monotonicOverride) + 2_000
                    assertTrue("Native text edit renewed inactivity deadline", it.checkSessionDeadline())
                    assertNotNull(it.currentSession!!.activeVault)
                }
                var next: View? = null
                awaitNative {
                    next = nativeView("inputExtractAction")
                    next != null
                }
                instrumentation.runOnMainSync {
                    assertTrue("Native Next/Done accepted", next!!.performClick())
                }
                awaitNative { !SyntheticFullscreenIme.active }
            }
            nativeField("synthetic-service")
            awaitNative { state(scenario) { _, s -> s.wizardState.step == AccountStep.ACCOUNT } }
            scenario.onActivity { a -> assertEquals("synthetic-service", a.currentSession!!.wizardState.issuer); assertNotNull(a.currentSession!!.activeVault) }
            nativeField("synthetic-account")
            awaitNative { state(scenario) { _, s -> s.wizardState.step == AccountStep.SECRET } }
            nativeField(KEY)
            awaitNative { state(scenario) { _, s -> !s.operationPending && s.currentScreen == Screen.MAIN } }
            assertEquals(listOf("synthetic old", "synthetic-account"), Vault.load(file, PASSWORD).entries.map { it.accountName })
        } finally {
            if (original.isNotBlank() && original != "null") shell("ime set ${quote(original)}")
            if (!wasEnabled) shell("ime disable ${quote(ime)}")
            if (showWithHardware == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard ${quote(showWithHardware)}")
        }
    }

    companion object {
        private const val PASSWORD = "Synthetic-Lifecycle-42!"
        private const val KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}
