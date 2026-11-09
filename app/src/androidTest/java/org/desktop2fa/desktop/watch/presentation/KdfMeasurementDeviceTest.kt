package org.desktop2fa.desktop.watch.presentation

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.testing.SyntheticMainActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Opt-in measurement, never a production benchmark or a KDF parameter change. */
class KdfMeasurementDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private data class State(val pending: Boolean, val active: Boolean, val count: Int, val screen: Screen)

    @Test fun measureSyntheticSessionOperations() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("d2faBenchmark")
        assumeTrue("Explicit isolated-device benchmark opt-in required", mode in setOf("physical", "emulator-smoke"))
        val emulator = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        ).use { it.readBytes().toString(Charsets.UTF_8).trim() == "1" }
        check(if (mode == "physical") !emulator else emulator) { "Benchmark mode/device mismatch" }
        val warmRounds = if (emulator) 0 else (args.getString("warmRounds") ?: "5").toInt().also { require(it in 1..20) }
        val batchSize = if (emulator) 2 else (args.getString("batchSize") ?: "32").toInt().also { require(it in 1..32) }
        val sourceCommit = requireNotNull(args.getString("sourceCommit")) { "Record the exact source commit" }
        require(sourceCommit.matches(Regex("[0-9a-f]{40}")))
        val app = instrumentation.targetContext
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        val samples = JSONArray()
        val report = JSONObject().put("schema", 1).put("mode", mode).put("sourceCommit", sourceCommit)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT).put("os", Build.VERSION.RELEASE)
            .put("build", Build.ID).put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("fixtureSha256", FIXTURE_SHA256).put("warmRounds", warmRounds).put("batchSize", batchSize)
            .put("samples", samples).put("completed", false)
        var scenario: ActivityScenario<SyntheticMainActivity>? = null
        val handler = Handler(Looper.getMainLooper())
        try {
            val fixture = instrumentation.context.assets.open("kdf/supported.d2fa").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
            check(hash == FIXTURE_SHA256) { "Synthetic fixture hash mismatch" }
            File(directory, "vault.d2fa").writeBytes(fixture) // No KDF before first measured unlock.
            val host = ActivityScenario.launch<SyntheticMainActivity>(Intent(app, SyntheticMainActivity::class.java)
                .putExtra("syntheticDirectory", directory.name))
            scenario = host
            host.onActivity { a ->
                // Isolate foreground latency from display sleep; session time still runs.
                a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                a.beforeIo = { check(Looper.myLooper() != Looper.getMainLooper()) { "Storage reached UI thread" } }
            }
            fun state(): State {
                var result: State? = null
                host.onActivity { a -> a.currentSession?.let { s ->
                    result = State(s.operationPending, s.activeVault != null, s.activeVault?.entries?.size ?: 0, s.currentScreen)
                } }
                return result ?: State(true, false, 0, Screen.SPLASH)
            }
            fun awaitIdle(): State {
                val deadline = SystemClock.elapsedRealtime() + 180_000
                var value = state()
                while (value.pending && SystemClock.elapsedRealtime() < deadline) { Thread.sleep(100); value = state() }
                check(!value.pending) { "Synthetic storage measurement did not settle" }
                return value
            }
            fun input(body: VaultSession.() -> Unit) {
                host.onActivity { a ->
                    val s = requireNotNull(a.currentSession)
                    a.userAction(s, s.uiToken()) { s.body() }
                }
            }
            // Wait for initial composition/startup; neither copying nor inspecting derives a key.
            val startupDeadline = SystemClock.elapsedRealtime() + 60_000
            while (state().screen != Screen.UNLOCK && SystemClock.elapsedRealtime() < startupDeadline) Thread.sleep(100)
            check(awaitIdle().screen == Screen.UNLOCK) { "Synthetic startup was not closed" }
            fun measure(phase: String, round: Int, expectedCount: Int, body: VaultSession.() -> Unit) {
                var maxGap = 0L
                var beats = 0
                var lastBeat = SystemClock.elapsedRealtime()
                val heartbeat = object : Runnable {
                    override fun run() {
                        val now = SystemClock.elapsedRealtime()
                        maxGap = maxOf(maxGap, now - lastBeat)
                        lastBeat = now
                        beats++
                        handler.postDelayed(this, 100)
                    }
                }
                var peakPss = 0
                var memorySamples = 0
                fun sampleMemory() {
                    val memory = Debug.MemoryInfo()
                    Debug.getMemoryInfo(memory)
                    peakPss = maxOf(peakPss, memory.totalPss)
                    memorySamples++
                }
                sampleMemory()
                val baselinePss = peakPss
                val started = SystemClock.elapsedRealtime()
                handler.post(heartbeat)
                var result: State? = null
                try {
                    input(body)
                    val deadline = started + 180_000
                    do {
                        sampleMemory()
                        result = state()
                        if (!result.pending) break
                        Thread.sleep(100)
                    } while (SystemClock.elapsedRealtime() < deadline)
                } finally {
                    instrumentation.runOnMainSync { handler.removeCallbacks(heartbeat) }
                    samples.put(JSONObject().put("phase", phase).put("round", round)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("baselinePssKiB", baselinePss).put("sampledPeakPssKiB", peakPss)
                        .put("memorySamples", memorySamples).put("mainBeats", beats).put("maxMainGapMs", maxGap)
                        .put("settled", result?.pending == false).put("active", result?.active == true)
                        .put("count", result?.count ?: -1).put("screen", result?.screen?.name ?: "unbound"))
                }
                assertTrue("Measured operation preserves the expected synthetic snapshot",
                    result?.let { !it.pending && it.active && it.screen == Screen.MAIN && it.count == expectedCount } == true)
            }
            var count = 13
            for (round in 0..warmRounds) {
                if (round > 0) { input { lock() }; check(awaitIdle().screen == Screen.UNLOCK) }
                measure(if (round == 0) "first-process-unlock" else "warm-unlock", round, count) {
                    passwordInput = PASSWORD; unlock()
                }
                count++
                measure("manual-save", round, count) {
                    addAccount()
                    wizardState = AccountWizardState(AccountStep.SECRET, "synthetic benchmark", "synthetic manual $round", KEY)
                    submitAccount()
                }
                count += batchSize
                measure("batch-commit", round, count) {
                    submitAdditions((0 until batchSize).map { i ->
                        TotpEntry(issuer = "synthetic benchmark", accountName = "synthetic batch $round $i", secret = KEY)
                    })
                }
            }
            report.put("completed", true)
        } finally {
            var pending = false
            scenario?.onActivity { pending = it.currentSession?.operationPending == true }
            report.put("cleanupDeferredForPendingIo", pending)
            // Only non-secret timing/count/device metadata crosses instrumentation status.
            instrumentation.sendStatus(0, Bundle().apply { putString("d2faBenchmarkResult", report.toString()) })
            scenario?.close()
            if (!pending) {
                check(VaultSecurityManager.clearPassword(SyntheticWatchContext(app, directory)))
                Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
                Files.delete(directory.toPath())
            } // Never remove a file/directory while its synchronous writer may finish.
        }
    }

    companion object {
        private const val FIXTURE_SHA256 = "a819a0f6db135b622ef0d09c78b7eee6affe7c569a31f4f1c5f52205327e4291"
        private const val PASSWORD = "Synthetic-Żółw-🔐-42!"
        private const val KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}
