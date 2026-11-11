package org.desktop2fa.desktop.watch.presentation

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
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
        val app = instrumentation.targetContext
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}")
        check(directory.mkdir())
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        var pending = false
        try {
            val fixture = instrumentation.context.assets.open("kdf/supported.d2fa").use { it.readBytes() }
            check(MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) } ==
                "a819a0f6db135b622ef0d09c78b7eee6affe7c569a31f4f1c5f52205327e4291")
            File(directory, "vault.d2fa").writeBytes(fixture)
            val host = ActivityScenario.launch<SyntheticMainActivity>(Intent(app, SyntheticMainActivity::class.java)
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
            await { a, s -> a.hasWindowFocus() && !s.operationPending && s.currentScreen == Screen.UNLOCK }
            host.onActivity { a ->
                val s = requireNotNull(a.currentSession)
                a.userAction(s, s.uiToken()) { s.passwordInput = "Synthetic-Żółw-🔐-42!"; s.unlock() }
            }
            await { a, s -> a.codesActive && !s.operationPending && s.currentScreen == Screen.MAIN }
            instrumentation.sendStatus(0, Bundle().apply { putString("d2faManualObservation", "READY_SYNTHETIC_UI") })
            // Real session/lifecycle clocks, display sleep and OEM keyboard remain untouched.
            Thread.sleep(180_000)
        } finally {
            scenario?.onActivity { pending = it.currentSession?.operationPending == true }
            scenario?.close()
            if (!pending) {
                check(VaultSecurityManager.clearPassword(SyntheticWatchContext(app, directory)))
                Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
                Files.delete(directory.toPath())
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("d2faManualCleanup", if (pending) "DEFERRED_IO" else "DONE") })
        }
    }
}
