package org.desktop2fa.desktop.watch.presentation

import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import org.desktop2fa.desktop.watch.vault.PasswordStrengthValidator
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import java.io.File

open class MainActivity : ComponentActivity() {
    /** Debug test hosts can isolate storage; production always uses the application context. */
    protected open fun vaultContext(): Context = applicationContext

    internal var currentSession: VaultSession? = null
        private set

    internal open fun createSessionStorage(context: Context): VaultSessionStorage =
        VaultSessionStorage(File(context.filesDir, "vault.d2fa")) { VaultSecurityManager.clearPassword(context) }

    protected open fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
    protected open fun wallClockMillis(): Long = System.currentTimeMillis()
    private val lockPolicy = SessionLockPolicy({ monotonicMillis() })
    private var timeoutJob: Job? = null
    private var started = false
    private var resumed by mutableStateOf(false)
    private var focused by mutableStateOf(false)
    private var deviceReady by mutableStateOf(false)
    private var clockRevision by mutableStateOf(0)
    private var lockBeforeBinding = false
    private var receiverRegistered = false
    private var unavailableLocked = false
    internal val codesActive: Boolean get() = resumed && focused && deviceReady
    internal fun notifyWallClockChanged() { checkSessionDeadline(); clockRevision++ }

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                deviceReady = false
                unavailableLocked = true
                invalidateSession()
            } else {
                checkSessionDeadline()
                clockRevision++
            }
        }
    }

    private fun invalidateSession() {
        lockPolicy.locked()
        timeoutJob?.cancel()
        val session = currentSession
        if (session == null) lockBeforeBinding = true else session.lock()
    }

    internal fun checkSessionDeadline(): Boolean {
        // A recreated window can already own focus without another Activity
        // callback. Reconcile before accepting input or publishing I/O results.
        focused = hasWindowFocus()
        deviceReady = getSystemService(PowerManager::class.java).isInteractive &&
            // Wear OS may keep an insecure Keyguard showing but occluded by the
            // app. That is not an authentication-locked device (isDeviceLocked).
            !getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!deviceReady) {
            // Cleanup may finish while the display stays off. Do not repeatedly
            // invalidate its new generation and schedule another cleanup.
            if (!unavailableLocked) { unavailableLocked = true; invalidateSession() }
            return false
        }
        unavailableLocked = false
        if (lockPolicy.expired()) { invalidateSession(); return false }
        return true
    }

    private fun scheduleDeadline() {
        timeoutJob?.cancel()
        val remaining = lockPolicy.remainingMillis() ?: return
        timeoutJob = lifecycleScope.launch {
            delay(remaining.coerceAtLeast(1))
            checkSessionDeadline()
            scheduleDeadline()
        }
    }

    private fun input(): Boolean {
        if (!checkSessionDeadline()) return false
        if (!started || !deviceReady) return false
        if (!lockPolicy.input()) { invalidateSession(); return false }
        scheduleDeadline()
        return true
    }

    internal fun userAction(session: VaultSession, token: Any, action: () -> Unit) {
        if (currentSession === session && session.acceptsUiToken(token) && input() && session.acceptsUiToken(token)) action()
    }

    override fun onStart() {
        super.onStart()
        started = true
        lockPolicy.start()
        checkSessionDeadline()
        scheduleDeadline()
    }

    override fun onResume() {
        super.onResume()
        checkSessionDeadline()
        clockRevision++
        resumed = true
        scheduleDeadline()
    }

    override fun onPause() {
        resumed = false // Pause/focus loss alone is not departure, including extract IME.
        checkSessionDeadline() // Device lock/expired time still wins over an IME transition.
        super.onPause()
    }

    override fun onStop() {
        resumed = false
        started = false
        lockPolicy.stop()
        invalidateSession()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        checkSessionDeadline()
        // The callback is authoritative during the focus transition itself.
        focused = hasFocus
        if (hasFocus) clockRevision++
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE) && !input()) return true
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !input()) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && !input()) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        if (receiverRegistered) unregisterReceiver(systemEvents)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Protect every screen, including transitions and unmasked key entry.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(systemEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(systemEvents, filter)
        receiverRegistered = true

        setContent {
            val context = vaultContext()
            val coroutineScope = rememberCoroutineScope()

            val session = remember {
                VaultSession(coroutineScope, createSessionStorage(context), beforePublish = { checkSessionDeadline() },
                    onLocked = { lockPolicy.locked(); timeoutJob?.cancel() })
            }
            val uiToken = session.uiToken()
            val act: (() -> Unit) -> Unit = { action -> userAction(session, uiToken, action) }
            DisposableEffect(session) {
                currentSession = session
                if (lockBeforeBinding) { lockBeforeBinding = false; session.lock() }
                onDispose {
                    session.dispose()
                    if (currentSession === session) currentSession = null
                }
            }
            LaunchedEffect(session) { session.startup() }
            val scrollState = rememberScrollState()
            with(session) {
                if (isLoading || currentScreen == Screen.SPLASH) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0F172A))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "desktop-2fa", color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Loading Secure Vault...", color = Color(0xFF3B82F6))
                        operationNotice?.let { Text(it, color = Color(0xFFFCA5A5)) }
                        Button(onClick = { act { lock() } }) { Text("Lock Vault") }
                    }
                } else {
                    when (currentScreen) {
                        Screen.SPLASH -> {}

                        Screen.STORAGE_ERROR -> StorageErrorScreen(
                            listOfNotNull(errorMessage, cacheWarning, operationNotice).joinToString("\n").ifBlank { null },
                            onRetry = { act { startup() } }, onRecovery = { act { listRecovery() } }, busy = operationPending,
                        )

                        Screen.RECOVERY -> RecoveryScreen(
                            candidates = recoveryCandidates,
                            password = passwordInput,
                            error = listOfNotNull(errorMessage, cacheWarning).joinToString("\n").ifBlank { null },
                            onPasswordChange = { value -> act { if (!operationPending) { passwordInput = value; errorMessage = null } } },
                            onOpen = { candidate -> act { openRecovery(candidate) } },
                            onBack = { act { leaveRecovery() } },
                            busy = operationPending,
                        )

                        Screen.SETUP -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0F172A))
                                    .verticalScroll(scrollState)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = "Create Vault", color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = passwordInput,
                                    enabled = !operationPending,
                                    onValueChange = { value -> act {
                                        if (!operationPending) {
                                            passwordInput = value
                                            val res = PasswordStrengthValidator.evaluate(value)
                                            errorMessage = if (!res.isValid) res.feedback else null
                                        }
                                    } },
                                    label = { Text("Master Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        autoCorrectEnabled = false,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { act { setup() } }
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF3B82F6),
                                        unfocusedBorderColor = Color(0xFF9CA3AF)
                                    )
                                )
                                if (errorMessage != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = errorMessage!!, color = Color(0xFFEF4444))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                cacheWarning?.let { Text(it, color = Color(0xFFFCA5A5)) }
                                operationNotice?.let { Text(it, color = Color(0xFFFCA5A5)) }
                                Text("Password required for every session. Remember is no longer available.", color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { act { setup() } }
                                ) {
                                    Text("Initialize Vault")
                                }
                            }
                        }

                        Screen.UNLOCK -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0F172A))
                                    .verticalScroll(scrollState)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = "Unlock Vault", color = Color.White)
                                if (operationPending) {
                                    Text(if (clearingCache) "Removing remembered password…" else "Finishing previous operation…", color = Color.White)
                                }
                                operationNotice?.let { Text(it, color = Color(0xFFFCA5A5)) }
                                Text("Password required for every session. Remember is no longer available.", color = Color.White)
                                cacheWarning?.let { Text(it, color = Color(0xFFFCA5A5)) }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = passwordInput,
                                    enabled = !operationPending,
                                    onValueChange = { value -> act { if (!operationPending) passwordInput = value } },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        autoCorrectEnabled = false,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { act { unlock() } }
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF3B82F6),
                                        unfocusedBorderColor = Color(0xFF9CA3AF)
                                    )
                                )
                                if (errorMessage != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = errorMessage!!, color = Color(0xFFEF4444))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { act { unlock() } },
                                    enabled = !operationPending
                                ) {
                                    Text("Unlock")
                                }
                            }
                        }

                        Screen.MAIN -> {
                            var tick by remember { mutableStateOf(0L) }
                            val currentTime = remember(tick, codesActive, clockRevision) {
                                if (codesActive) wallClockMillis() / 1000 else 0L
                            }
                            LaunchedEffect(codesActive, clockRevision) {
                                if (codesActive) while (true) {
                                    delay(1000L - Math.floorMod(wallClockMillis(), 1000L))
                                    tick++
                                }
                            }

                            val listState = rememberTransformingLazyColumnState(
                                initialAnchorItemIndex = addedAccountIndex?.plus(1) ?: 0
                            )
                            val entries = activeVault?.entries ?: emptyList()

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0F172A))
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                TransformingLazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    item {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(text = "desktop-2fa", color = Color.White)
                                            recoverySource?.let { source ->
                                                Text("Recovery view: $source. Read only; no files changed.", color = Color(0xFFFCA5A5))
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                    }

                                    if (entries.isEmpty()) {
                                        item {
                                            Text(text = "No accounts", color = Color(0xFF9CA3AF))
                                        }
                                    } else {
                                        // Legacy vaults may contain duplicate names and secrets. The
                                        // current list only appends entries, so positions stay stable.
                                        itemsIndexed(entries, key = { index, _ -> "entry-$index" }) { index, entry ->

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    .background(Color(0xFF1E293B), shape = RoundedCornerShape(16.dp))
                                                    .padding(12.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                if (index == addedAccountIndex && successMessage != null) {
                                                    Text("Account added", color = Color(0xFF10B981))
                                                }
                                                Text(text = entry.issuer ?: "Service", color = Color(0xFF3B82F6))
                                                Text(text = entry.accountName ?: "", color = Color(0xFF9CA3AF))
                                                Spacer(modifier = Modifier.height(4.dp))
                                                if (codesActive) AccountCode(accountCodeState(entry, currentTime))
                                                else Text("Codes paused", color = Color(0xFF9CA3AF))
                                            }
                                        }
                                    }

                                    item {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            AddAccountAction(recoveryReadOnly = recoverySource != null, onAdd = { act { addAccount() } })
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Button(onClick = { act { lock() } }) { Text("Lock Vault") }
                                            if (successMessage != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(text = successMessage!!, color = Color(0xFF10B981))
                                            }
                                            if (errorMessage != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(text = errorMessage!!, color = Color(0xFFEF4444))
                                            }
                                            cacheWarning?.let { Text(it, color = Color(0xFFFCA5A5)) }
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Screen.ADD_ACCOUNT -> {
                            AccountWizard(
                                state = wizardState,
                                onEdit = { value -> act { if (!operationPending) wizardState = wizardState.edit(value) } },
                                onSubmit = { act { submitAccount() } },
                                onBack = { act { if (!operationPending) wizardState = wizardState.back() } },
                                onCancel = { act { cancelDraft() } },
                                onLock = { act { lock() } },
                                notice = operationNotice,
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class Screen {
    SPLASH, SETUP, UNLOCK, MAIN, ADD_ACCOUNT, STORAGE_ERROR, RECOVERY
}
