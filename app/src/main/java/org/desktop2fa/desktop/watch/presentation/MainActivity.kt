package org.desktop2fa.desktop.watch.presentation

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent {
            val context = vaultContext()
            val coroutineScope = rememberCoroutineScope()

            val session = remember { VaultSession(coroutineScope, createSessionStorage(context)) }
            DisposableEffect(session) {
                currentSession = session
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
                        Button(onClick = { lock() }) { Text("Lock Vault") }
                    }
                } else {
                    when (currentScreen) {
                        Screen.SPLASH -> {}

                        Screen.STORAGE_ERROR -> StorageErrorScreen(
                            listOfNotNull(errorMessage, cacheWarning, operationNotice).joinToString("\n").ifBlank { null },
                            onRetry = { startup() }, onRecovery = { listRecovery() }, busy = operationPending,
                        )

                        Screen.RECOVERY -> RecoveryScreen(
                            candidates = recoveryCandidates,
                            password = passwordInput,
                            error = listOfNotNull(errorMessage, cacheWarning).joinToString("\n").ifBlank { null },
                            onPasswordChange = { if (!operationPending) { passwordInput = it; errorMessage = null } },
                            onOpen = { openRecovery(it) },
                            onBack = { leaveRecovery() },
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
                                    onValueChange = {
                                        if (!operationPending) {
                                            passwordInput = it
                                            val res = PasswordStrengthValidator.evaluate(it)
                                            errorMessage = if (!res.isValid) res.feedback else null
                                        }
                                    },
                                    label = { Text("Master Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        autoCorrectEnabled = false,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { setup() }
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
                                    onClick = { setup() }
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
                                    onValueChange = { if (!operationPending) passwordInput = it },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        autoCorrectEnabled = false,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { unlock() }
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
                                    onClick = { unlock() },
                                    enabled = !operationPending
                                ) {
                                    Text("Unlock")
                                }
                            }
                        }

                        Screen.MAIN -> {
                            var currentTime by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
                            LaunchedEffect(Unit) {
                                while (true) {
                                    delay(1000L)
                                    currentTime = System.currentTimeMillis() / 1000
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
                                            val codeState = accountCodeState(entry, currentTime)

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
                                                AccountCode(codeState)
                                            }
                                        }
                                    }

                                    item {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            AddAccountAction(recoveryReadOnly = recoverySource != null, onAdd = { addAccount() })
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Button(onClick = { lock() }) { Text("Lock Vault") }
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
                                onEdit = { if (!operationPending) wizardState = wizardState.edit(it) },
                                onSubmit = { submitAccount() },
                                onBack = { if (!operationPending) wizardState = wizardState.back() },
                                onCancel = { cancelDraft() },
                                onLock = { lock() },
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
