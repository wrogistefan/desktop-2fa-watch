package org.desktop2fa.desktop.watch.presentation

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScreenCaptureDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue(message, condition())
    }

    private fun capturedMarker(): Boolean {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return false
        try {
            check(image.width >= 80 && image.height >= 80)
            var matching = 0
            for (y in image.height / 2 - 20 until image.height / 2 + 20) {
                for (x in image.width / 2 - 20 until image.width / 2 + 20) {
                    val pixel = image.getPixel(x, y)
                    if (Color.red(pixel) > 230 && Color.green(pixel) < 25 && Color.blue(pixel) > 230) matching++
                }
            }
            return matching > 1_440
        } finally { image.recycle() }
    }

    @Test fun compositorHidesTheWindowAndRecreationKeepsProtection() {
        val app = instrumentation.targetContext
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        try {
            val host = ActivityScenario.launch<SyntheticMainActivity>(Intent(app, SyntheticMainActivity::class.java)
                .putExtra("syntheticDirectory", directory.name))
            scenario = host
            fun marker() {
                await("Synthetic window is focused and drawn") {
                    var ready = false
                    host.onActivity { a ->
                        ready = a.hasWindowFocus() && a.window.decorView.width > 0
                        if (ready) a.window.decorView.foreground = ColorDrawable(Color.MAGENTA)
                    }
                    ready
                }
                instrumentation.waitForIdleSync()
            }
            marker()
            assertFalse("Default secure window excludes synthetic marker", capturedMarker())

            // Calibration changes only this disposable host. Never capture a vault,
            // password, account or code; the opaque marker covers the entire window.
            host.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            await("Unprotected calibration surface is captured") { capturedMarker() }
            host.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            await("Restored protection excludes marker") { !capturedMarker() }

            host.recreate()
            marker()
            assertFalse("Recreated secure window excludes marker", capturedMarker())
        } finally {
            // Restore the flag even when a calibration assertion fails.
            scenario?.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            scenario?.close()
            check(VaultSecurityManager.clearPassword(SyntheticWatchContext(app, directory)))
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }
}
