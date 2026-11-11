package org.desktop2fa.desktop.watch.presentation

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Holds a synthetic production UI for human observation; passing is not visual evidence. */
class ManualObservationDeviceTest {
    @Test fun presentSyntheticAccountsForOwnerObservation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit manual observation opt-in", InstrumentationRegistry.getArguments()
            .getString("d2faManualObservation") == "synthetic")
        val surface = InstrumentationRegistry.getArguments().getString("d2faManualSurface") ?: "accounts"
        require(surface in setOf("accounts", "wizard", "key", "lock-check", "save-lock"))
        val observationSeconds = (InstrumentationRegistry.getArguments().getString("d2faManualSeconds") ?: "180")
            .toLong().also { require(it in 60..600) }
        val app = instrumentation.targetContext
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}")
        check(directory.mkdir())
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        var pending = false
        var originalEntries = emptyList<TotpEntry>()
        var retainForReview = false
        try {
            instrumentation.sendStatus(0, Bundle().apply { putString("d2faManualControlDirectory", directory.name) })
            val fixture = instrumentation.context.assets.open("kdf/supported.d2fa").use { it.readBytes() }
            check(MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) } ==
                "a819a0f6db135b622ef0d09c78b7eee6affe7c569a31f4f1c5f52205327e4291")
            File(directory, "vault.d2fa").writeBytes(fixture)
            val host = ActivityScenario.launch<SyntheticMainActivity>(Intent(app, SyntheticMainActivity::class.java)
                .putExtra("syntheticKeepScreenOn", surface in setOf("wizard", "key", "lock-check", "save-lock"))
                .putExtra("syntheticDirectory", directory.name))
            scenario = host
            fun await(condition: (SyntheticMainActivity, VaultSession) -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 180_000
                var ready: Boolean
                do {
                    ready = false
                    host.onActivity { a -> a.currentSession?.let { ready = condition(a, it) } }
                    if (!ready) Thread.sleep(100)
                } while (!ready && SystemClock.elapsedRealtime() < deadline)
                check(ready) { "Synthetic manual host did not become ready" }
            }
            var retriedStartup = false
            await { a, s ->
                // A launch/ambient transition can invalidate the initial inspection.
                // Retry only that synthetic inspection once through the production guard.
                if (!retriedStartup && a.codesActive && !s.operationPending && s.currentScreen == Screen.STORAGE_ERROR) {
                    retriedStartup = true
                    a.userAction(s, s.uiToken()) { s.startup() }
                }
                a.hasWindowFocus() && !s.operationPending && s.currentScreen == Screen.UNLOCK
            }
            host.onActivity { a ->
                val s = requireNotNull(a.currentSession)
                a.userAction(s, s.uiToken()) { s.passwordInput = "Synthetic-Żółw-🔐-42!"; s.unlock() }
            }
            await { a, s -> a.codesActive && !s.operationPending && s.currentScreen == Screen.MAIN }
            host.onActivity { originalEntries = requireNotNull(it.currentSession?.activeVault).entries.toList() }
            if (surface in setOf("wizard", "key", "save-lock")) host.onActivity { a ->
                val s = requireNotNull(a.currentSession)
                a.userAction(s, s.uiToken()) {
                    s.addAccount()
                    s.wizardState = AccountWizardState(if (surface == "wizard") AccountStep.SERVICE else AccountStep.SECRET,
                        "synthetic service", "synthetic manual account",
                        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("d2faManualObservation", "READY_SYNTHETIC_UI_$surface")
                putString("d2faManualControlDirectory", directory.name)
            })
            // Session clocks and OEM keyboard remain untouched. Key observation
            // releases screen-on only after the controller confirms the key is shown.
            val until = SystemClock.elapsedRealtime() + observationSeconds * 1_000
            val finish = File(directory, "observation-finished")
            var releasedDisplay = false
            while (!finish.exists() && SystemClock.elapsedRealtime() < until) {
                if (surface == "key" && !releasedDisplay && File(directory, "observation-allow-sleep").exists()) {
                    host.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    releasedDisplay = true
                    instrumentation.sendStatus(0, Bundle().apply { putString("d2faManualDisplay", "SLEEP_ALLOWED") })
                }
                Thread.sleep(1_000)
            }
        } finally {
            scenario?.onActivity { pending = it.currentSession?.operationPending == true }
            if (surface == "save-lock" && !pending) {
                var count = -1
                val verified = try {
                    val saved = Vault.load(File(directory, "vault.d2fa"), "Synthetic-Żółw-🔐-42!")
                    count = saved.entries.size
                    originalEntries.size == 13 && count == 14 && saved.entries.take(13) == originalEntries
                } catch (_: Exception) { false }
                retainForReview = !verified
                instrumentation.sendStatus(0, Bundle().apply {
                    putInt("d2faManualFinalRecordCount", count)
                    putBoolean("d2faManualOriginalRecordsPreserved", verified)
                })
            }
            scenario?.close()
            if (!pending && !retainForReview) {
                check(VaultSecurityManager.clearPassword(SyntheticWatchContext(app, directory)))
                Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
                Files.delete(directory.toPath())
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("d2faManualCleanup", if (pending) "DEFERRED_IO" else if (retainForReview) "RETAINED_FOR_REVIEW" else "DONE")
            })
            check(!retainForReview) { "Synthetic save-lock snapshot requires review" }
        }
    }
}
