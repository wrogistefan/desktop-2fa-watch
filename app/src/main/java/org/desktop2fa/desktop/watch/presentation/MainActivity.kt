package org.desktop2fa.desktop.watch.presentation

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.vault.CorruptedVaultException
import org.desktop2fa.desktop.watch.vault.InvalidPasswordException
import org.desktop2fa.desktop.watch.vault.PasswordStrengthValidator
import org.desktop2fa.desktop.watch.vault.UnsupportedFormatException
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultIOException
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent {
            val context = applicationContext
            val vaultFile = remember { File(context.filesDir, "vault.d2fa") }
            val coroutineScope = rememberCoroutineScope()

            var activeVault by remember { mutableStateOf<Vault?>(null) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var successMessage by remember { mutableStateOf<String?>(null) }
            var passwordInput by remember { mutableStateOf("") }
            var saveToKeystore by remember { mutableStateOf(true) }
            var isLoading by remember { mutableStateOf(true) }
            var startupAttempt by remember { mutableStateOf(0) }
            var cacheWarning by remember { mutableStateOf<String?>(null) }
            var clearingCache by remember { mutableStateOf(false) }

            var wizardState by remember { mutableStateOf(AccountWizardState()) }
            var addedAccountIndex by remember { mutableStateOf<Int?>(null) }

            var currentScreen by remember { mutableStateOf(Screen.SPLASH) }

            // Asynchronous startup check off main thread
            LaunchedEffect(startupAttempt) {
                withContext(Dispatchers.IO) {
                    val startup = resolveVaultStartup(vaultFile, readCachedPassword = {
                        VaultSecurityManager.getPassword(context)
                    })
                    withContext(Dispatchers.Main) {
                        activeVault = startup.vault
                        currentScreen = startup.screen
                        errorMessage = startup.errorMessage
                        isLoading = false
                    }
                }
            }

            val scrollState = rememberScrollState()

            // Helper action for setup
            val handleSetup = setup@{
                if (isLoading) return@setup
                if (passwordInput.isBlank()) {
                    errorMessage = "Password cannot be empty"
                } else {
                    val res = PasswordStrengthValidator.evaluate(passwordInput)
                    if (res.score >= 3 && res.isValid) {
                        isLoading = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val newVault = Vault.create()
                                newVault.saveNew(vaultFile, passwordInput)
                                val cacheSaved = if (saveToKeystore) {
                                    VaultSecurityManager.savePassword(context, passwordInput)
                                } else {
                                    VaultSecurityManager.clearPassword(context)
                                }
                                withContext(Dispatchers.Main) {
                                    activeVault = newVault
                                    currentScreen = Screen.MAIN
                                    errorMessage = null
                                    cacheWarning = cacheFailureMessage(cacheSaved, saveToKeystore)
                                    isLoading = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = "Could not create a new vault. Existing data has not been replaced."
                                    currentScreen = Screen.STORAGE_ERROR
                                    isLoading = false
                                }
                            }
                        }
                    } else {
                        errorMessage = res.feedback
                    }
                }
            }

            val handleUnlock = unlock@{
                if (isLoading || clearingCache) return@unlock
                if (passwordInput.isBlank()) {
                    errorMessage = "Password cannot be empty"
                } else {
                    isLoading = true
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val loaded = Vault.load(vaultFile, passwordInput)
                            val cacheSaved = if (saveToKeystore) {
                                VaultSecurityManager.savePassword(context, passwordInput)
                            } else {
                                VaultSecurityManager.clearPassword(context)
                            }
                            withContext(Dispatchers.Main) {
                                activeVault = loaded
                                currentScreen = Screen.MAIN
                                errorMessage = null
                                cacheWarning = cacheFailureMessage(cacheSaved, saveToKeystore)
                                isLoading = false
                            }
                        } catch (_: InvalidPasswordException) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Invalid password"
                                isLoading = false
                            }
                        } catch (_: CorruptedVaultException) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Vault contains invalid or unsupported data"
                                isLoading = false
                            }
                        } catch (_: UnsupportedFormatException) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Unsupported vault format"
                                isLoading = false
                            }
                        } catch (e: VaultIOException) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "IO error: ${e.message}"
                                isLoading = false
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Unlock failed: ${e.message}"
                                isLoading = false
                            }
                        }
                    }
                }
            }

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
                }
            } else {
                when (currentScreen) {
                    Screen.SPLASH -> {}

                    Screen.STORAGE_ERROR -> StorageErrorScreen(errorMessage, onRetry = {
                        isLoading = true
                        startupAttempt += 1
                    })

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
                                onValueChange = {
                                    passwordInput = it
                                    val res = PasswordStrengthValidator.evaluate(it)
                                    errorMessage = if (!res.isValid) res.feedback else null
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
                                    onDone = { handleSetup() }
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = saveToKeystore,
                                    onCheckedChange = { saveToKeystore = it }
                                )
                                Spacer(modifier = Modifier.height(0.dp))
                                Text(text = "Remember on watch", color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { handleSetup() }
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
                            if (clearingCache) {
                                Text("Removing remembered password…", color = Color.White)
                            }
                            cacheWarning?.let { Text(it, color = Color(0xFFFCA5A5)) }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { handleUnlock() }
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = saveToKeystore,
                                    onCheckedChange = { saveToKeystore = it }
                                )
                                Text(text = "Remember on watch", color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { handleUnlock() },
                                enabled = !clearingCache
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
                                        Button(
                                            onClick = {
                                                wizardState = AccountWizardState()
                                                addedAccountIndex = null
                                                errorMessage = null
                                                successMessage = null
                                                currentScreen = Screen.ADD_ACCOUNT
                                            }
                                        ) {
                                            Text("Add Account")
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Button(
                                            onClick = {
                                                activeVault = null
                                                passwordInput = ""
                                                wizardState = AccountWizardState()
                                                addedAccountIndex = null
                                                errorMessage = null
                                                successMessage = null
                                                currentScreen = Screen.UNLOCK
                                                cacheWarning = null
                                                clearingCache = true

                                                coroutineScope.launch {
                                                    val cleared = withContext(Dispatchers.IO + NonCancellable) {
                                                        VaultSecurityManager.clearPassword(context)
                                                    }
                                                    clearingCache = false
                                                    cacheWarning = cacheFailureMessage(cleared, remember = false)
                                                }
                                            }
                                        ) {
                                            Text("Lock Vault")
                                        }
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
                        val handleSubmit = submit@{
                            if (wizardState.isSaving) return@submit
                            val vault = activeVault
                            if (vault == null) {
                                wizardState = wizardState.copy(error = "Unlock the vault before adding an account")
                                return@submit
                            }
                            val draft = wizardState.submit(vault.entries.map { it.accountName })
                            wizardState = draft
                            if (!draft.isSaving) return@submit

                            // Capture the validated draft and fallback password on the UI thread.
                            val fallbackPassword = passwordInput
                            coroutineScope.launch {
                                try {
                                    val reloaded = withContext(Dispatchers.IO) {
                                        val updated = Vault(VaultData(
                                            version = vault.data.version,
                                            entries = vault.data.entries.toMutableList()
                                        ))
                                        updated.addEntry(draft.account, draft.issuer, draft.secret)
                                        // Prefer the password that actually unlocked this session,
                                        // even if updating an older cache failed.
                                        val password = fallbackPassword.takeIf { it.isNotBlank() }
                                            ?: VaultSecurityManager.getPassword(context)
                                            ?: throw IllegalStateException("Unlock required")
                                        updated.save(vaultFile, password)
                                        Vault.load(vaultFile, password)
                                    }
                                    activeVault = reloaded
                                    addedAccountIndex = reloaded.entries.lastIndex
                                    successMessage = "Account added successfully!"
                                    errorMessage = null
                                    wizardState = AccountWizardState()
                                    currentScreen = Screen.MAIN
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Keep the draft for retry; do not expose provider messages or key data.
                                    wizardState = draft.copy(isSaving = false,
                                        error = "Could not save the account. Try again.")
                                }
                            }
                        }
                        AccountWizard(
                            state = wizardState,
                            onEdit = { wizardState = wizardState.edit(it) },
                            onSubmit = handleSubmit,
                            onBack = { wizardState = wizardState.back() },
                            onCancel = {
                                if (!wizardState.isSaving) {
                                    wizardState = AccountWizardState()
                                    currentScreen = Screen.MAIN
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

enum class Screen {
    SPLASH, SETUP, UNLOCK, MAIN, ADD_ACCOUNT, STORAGE_ERROR
}

internal fun cacheFailureMessage(success: Boolean, remember: Boolean): String? = when {
    success -> null
    remember -> "Could not remember the password. Keep your vault password for the next unlock."
    else -> "Could not confirm removal of the remembered password. It may still unlock the vault after restart."
}
