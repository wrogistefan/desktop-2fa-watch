package org.desktop2fa.transfer.poc

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.desktop2fa.transfer.NativeCore
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate UID and application ID. This Activity contains no vault/model/storage dependency. */
class PocActivity : Activity() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var token = Any()
    @Volatile private var started = false
    private var socket: Socket? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var attempt: Job? = null
    private var attempts = 0
    private var code: String? = null
    private var codeDeadline = Long.MAX_VALUE
    private var lastInput = 0L
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private lateinit var start: Button
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val expire = Runnable {
        if (attempt != null) end("Session expired. Start a new attempt.")
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { end("Test stopped. No accounts were transferred.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        attempts = savedInstanceState?.getInt("attempts", 0)?.coerceIn(0, 3) ?: 0
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val padding = (36 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding); isSaveEnabled = false
        }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text; textSize = size; gravity = Gravity.CENTER; isSaveEnabled = false
        }.also(layout::addView)
        label("D2FA transport test", 17f)
        label("Synthetic test only. No vault access.", 13f)
        address = EditText(this).apply {
            hint = "Computer IPv4:port"; isSingleLine = true; isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }.also(layout::addView)
        codeView = label("", 24f).apply { typeface = Typeface.MONOSPACE }
        status = label("Start the test server on your computer.", 13f)
        start = Button(this).apply { text = "Start test"; setOnClickListener { begin() }; isSaveEnabled = false }.also(layout::addView)
        Button(this).apply { text = "Cancel"; setOnClickListener { end("Test cancelled.") }; isSaveEnabled = false }.also(layout::addView)
        setContentView(ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(layout) })
    }
    override fun onSaveInstanceState(outState: Bundle) {
        // Only the nonsecret attempt count survives recreation. No views, codes or credentials.
        outState.putInt("attempts", attempts)
    }
    override fun onStart() {
        super.onStart(); started = true
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onPause() { codeView.text = ""; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::codeView.isInitialized) codeView.text = if (hasFocus && attempt != null && owns(token)) code.orEmpty() else ""
    }
    override fun onStop() {
        started = false; end("Test stopped. No accounts were transferred.")
        unregisterReceiver(screenOff); super.onStop()
    }
    override fun onDestroy() { owner.cancel(); super.onDestroy() }
    override fun onUserInteraction() {
        super.onUserInteraction()
        val now = SystemClock.elapsedRealtime()
        if (attempt != null && (now - lastInput >= 60_000 || now >= codeDeadline)) end("Session expired. Start a new attempt.")
        else { lastInput = now; scheduleExpiry() }
    }
    private fun scheduleExpiry() {
        handler.removeCallbacks(expire)
        if (attempt != null) handler.postDelayed(expire,
            (minOf(lastInput + 60_000, codeDeadline) - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    private fun end(message: String) {
        val old = synchronized(lock) { token = Any(); socket.also { socket = null } }
        try { old?.close() } catch (_: Exception) {}
        attempt?.cancel(); attempt = null
        callback?.let(::releaseWifi)
        code = null; codeDeadline = Long.MAX_VALUE; handler.removeCallbacks(expire)
        if (::codeView.isInitialized) { codeView.text = ""; status.text = message; start.isEnabled = attempts < 3; address.isEnabled = true }
    }
    private fun releaseWifi(value: ConnectivityManager.NetworkCallback) {
        if (callback === value) callback = null
        try { connectivity.unregisterNetworkCallback(value) } catch (_: IllegalArgumentException) {}
    }
    private fun owns(value: Any): Boolean = started && token === value &&
        SystemClock.elapsedRealtime() < minOf(codeDeadline, lastInput + 60_000) &&
        getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isDeviceLocked

    private fun requireOwner(value: Any) {
        if (!owns(value)) {
            if (token === value) end("Session expired or locked. Start a new attempt.")
            throw CancellationException()
        }
    }
    private fun begin() {
        if (!started || attempt != null || attempts >= 3) return
        // Android 17/API 37 enforces this runtime permission for target SDK 37.
        // https://developer.android.com/privacy-and-security/local-network-permission
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow local network access, then tap Start."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 1)
            return
        }
        val endpoint = try { PocMessages.endpoint(address.text.toString().trim()) }
        catch (_: Exception) { status.text = "Enter the computer's IPv4 address and port."; return }
        val current = Any().also { token = it }
        lastInput = SystemClock.elapsedRealtime(); codeDeadline = Long.MAX_VALUE
        start.isEnabled = false; address.isEnabled = false; address.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(address.windowToken, 0)
        status.text = "Acquiring Wi-Fi…"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var networkCallback: ConnectivityManager.NetworkCallback? = null
            try {
                val network = suspendCancellableCoroutine<Network> { continuation ->
                    val selected = AtomicBoolean(false)
                    var chosen: Network? = null
                    val requestCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            val caps = connectivity.getNetworkCapabilities(network)
                            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return
                            if (selected.compareAndSet(false, true)) { chosen = network; continuation.resume(network) }
                        }
                        override fun onUnavailable() {
                            if (selected.compareAndSet(false, true)) continuation.resumeWithException(PocFailure())
                        }
                        override fun onLost(network: Network) {
                            if (chosen == network) handler.post { if (token === current) end("Wi-Fi was lost. No fallback connection is used.") }
                        }
                    }
                    networkCallback = requestCallback; callback = requestCallback
                    continuation.invokeOnCancellation { releaseWifi(requestCallback) }
                    connectivity.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), requestCallback, 5000)
                }
                withContext(Dispatchers.IO) {
                    val connection = network.socketFactory.createSocket()
                    synchronized(lock) {
                        if (token !== current) { connection.close(); throw CancellationException() }
                        socket = connection
                    }
                    connection.use {
                        it.connect(InetSocketAddress(endpoint.first, endpoint.second), 5000)
                        val now = { SystemClock.elapsedRealtime() }
                        val sessionDeadline = now() + 15 * 60_000
                        val frames = PocFrames(now) { value -> it.soTimeout = value }
                        val random = SecureRandom()
                        val widBytes = ByteArray(16).also(random::nextBytes)
                        val wid = widBytes.joinToString("") { b -> "%02x".format(b.toInt() and 255) }
                        frames.write(it.getOutputStream(), PocMessages.message("START") {
                            put("profile", PROFILE); put("expected_sid", JsonNull); put("wid", wid)
                        }, 2048)
                        val challenge = frames.read(it.getInputStream(), now() + 5000, 2048)
                        PocMessages.expect(challenge, "CHALLENGE", setOf("profile", "sid", "wid"))
                        if (PocMessages.string(challenge, "profile") != PROFILE || PocMessages.id(challenge, "wid") != wid) throw PocFailure()
                        val sid = PocMessages.id(challenge, "sid")
                        val sidBytes = ByteArray(16) { n -> sid.substring(n * 2, n * 2 + 2).toInt(16).toByte() }
                        val digits = ByteArray(8) { ('0'.code + random.nextInt(10)).toByte() }
                        var handle = 0L
                        try {
                            ensureActive()
                            handle = NativeCore.create(0, sidBytes, widBytes, digits)
                            if (handle == 0L) throw PocFailure()
                            val a = NativeCore.message(handle) ?: throw PocFailure()
                            val expires = withContext(Dispatchers.Main) {
                                requireOwner(current)
                                attempts++
                                val raw = digits.toString(Charsets.US_ASCII)
                                code = raw.substring(0, 4) + " " + raw.substring(4)
                                codeDeadline = minOf(now() + 180_000, sessionDeadline)
                                codeView.text = code; status.text = "Enter this code on the computer."
                                scheduleExpiry(); codeDeadline
                            }
                            frames.write(it.getOutputStream(), PocMessages.message("PAKE_A") {
                                put("sid", sid); put("wid", wid); put("message", Base64.getEncoder().encodeToString(a))
                            }, 2048)
                            val b = frames.read(it.getInputStream(), expires, 2048)
                            PocMessages.expect(b, "PAKE_B", setOf("sid", "wid", "message"))
                            if (PocMessages.id(b, "sid") != sid || PocMessages.id(b, "wid") != wid) throw PocFailure()
                            if (NativeCore.finish(handle, PocMessages.point(b)) != 1) throw PocFailure()
                            val pipe = PocTlsPipe(it, handle, now)
                            pipe.handshake(minOf(now() + 15_000, expires))
                            digits.fill(0)
                            withContext(Dispatchers.Main) {
                                requireOwner(current)
                                code = null; codeView.text = ""; status.text = "Authenticated. Testing channel…"
                            }
                            pipe.write(PocMessages.message("HELLO") { put("session_id", sid) }, now() + 15_000)
                            val ready = pipe.read(now() + 15_000)
                            PocMessages.expect(ready, "READY", setOf("session_id", "max_accounts", "max_frame"))
                            if (PocMessages.id(ready, "session_id") != sid || ready["max_accounts"] != JsonPrimitive(50) ||
                                ready["max_frame"] != JsonPrimitive(262144)) throw PocFailure()
                            pipe.write(PocMessages.message("PING"), now() + 15_000)
                            PocMessages.expect(pipe.read(now() + 15_000), "PONG", emptySet())
                        } finally { digits.fill(0); if (handle != 0L) NativeCore.close(handle) }
                    }
                }
                if (token === current) end(if (owns(current)) "Authenticated test passed. No accounts were transferred."
                    else "Session expired or locked. Start a new attempt.")
            } catch (_: CancellationException) { /* Owner already clears the UI and closes the socket. */ }
            catch (_: Throwable) { if (token === current) end("Could not complete the test. Check Wi-Fi and start a new attempt.") }
            finally { networkCallback?.let(::releaseWifi) }
        }
        attempt = job
        scheduleExpiry()
        job.start()
    }
}
