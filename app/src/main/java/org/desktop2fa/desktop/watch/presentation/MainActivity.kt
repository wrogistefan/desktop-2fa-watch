package org.desktop2fa.desktop.watch.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
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

        setContent {
            val context = applicationContext
            val vaultFile = remember { File(context.filesDir, "vault.d2fa") }
            val coroutineScope = rememberCoroutineScope()
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            var activeVault by remember { mutableStateOf<Vault?>(null) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var successMessage by remember { mutableStateOf<String?>(null) }
            var passwordInput by remember { mutableStateOf("") }
            var saveToKeystore by remember { mutableStateOf(true) }
            var isLoading by remember { mutableStateOf(true) }

            // Add Account inputs
            var issuerInput by remember { mutableStateOf("") }
            var accountNameInput by remember { mutableStateOf("") }
            var secretInput by remember { mutableStateOf("") }

            var currentScreen by remember { mutableStateOf(Screen.SPLASH) }

            // Asynchronous startup check off main thread
            LaunchedEffect(Unit) {
                coroutineScope.launch(Dispatchers.IO) {
                    val vaultExists = vaultFile.exists()
                    val cachedPass = if (vaultExists) VaultSecurityManager.getPassword(context) else null
                    var loadedVault: Vault? = null
                    var nextScreen = Screen.SETUP

                    if (vaultExists) {
                        if (cachedPass != null) {
                            try {
                                loadedVault = Vault.load(vaultFile, cachedPass)
                                nextScreen = Screen.MAIN
                            } catch (_: Exception) {
                                nextScreen = Screen.UNLOCK
                            }
                        } else {
                            nextScreen = Screen.UNLOCK
                        }
                    }

                    withContext(Dispatchers.Main) {
                        activeVault = loadedVault
                        currentScreen = nextScreen
                        isLoading = false
                    }
                }
            }

            val scrollState = rememberScrollState()

            // Helper action for submit
            val handleSetup = {
                keyboardController?.hide()
                focusManager.clearFocus()
                if (passwordInput.isBlank()) {
                    errorMessage = "Password cannot be empty"
                } else {
                    val res = PasswordStrengthValidator.evaluate(passwordInput)
                    if (res.score >= 2 && res.isValid) {
                        isLoading = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val newVault = Vault.create()
                                newVault.save(vaultFile, passwordInput)
                                if (saveToKeystore) {
                                    VaultSecurityManager.savePassword(context, passwordInput)
                                } else {
                                    VaultSecurityManager.clearPassword(context)
                                }
                                withContext(Dispatchers.Main) {
                                    activeVault = newVault
                                    currentScreen = Screen.MAIN
                                    errorMessage = null
                                    isLoading = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = "Failed: ${e.message}"
                                    isLoading = false
                                }
                            }
                        }
                    } else {
                        errorMessage = res.feedback
                    }
                }
            }

            val handleUnlock = {
                keyboardController?.hide()
                focusManager.clearFocus()
                if (passwordInput.isBlank()) {
                    errorMessage = "Password cannot be empty"
                } else {
                    isLoading = true
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val loaded = Vault.load(vaultFile, passwordInput)
                            if (saveToKeystore) {
                                VaultSecurityManager.savePassword(context, passwordInput)
                            } else {
                                VaultSecurityManager.clearPassword(context)
                            }
                            withContext(Dispatchers.Main) {
                                activeVault = loaded
                                currentScreen = Screen.MAIN
                                errorMessage = null
                                isLoading = false
                            }
                        } catch (_: InvalidPasswordException) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Invalid password"
                                isLoading = false
                            }
                        } catch (_: CorruptedVaultException) {
                            withContext(Dispatchers.Main) {
                                errorMessage = "Vault file is corrupted"
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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A))
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading || currentScreen == Screen.SPLASH) {
                    Text(text = "desktop-2fa", color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Loading Secure Vault...", color = Color(0xFF3B82F6))
                } else {
                    when (currentScreen) {
                        Screen.SPLASH -> {
                            // Handled above
                        }

                        Screen.SETUP -> {
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

                        Screen.UNLOCK -> {
                            Text(text = "Unlock Vault", color = Color.White)
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
                                onClick = { handleUnlock() }
                            ) {
                                Text("Unlock")
                            }
                        }

                        Screen.MAIN -> {
                            Text(text = "desktop-2fa", color = Color.White)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Entries: ${activeVault?.entries?.size ?: 0}",
                                color = Color(0xFF3B82F6)
                            )
                            if (successMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = successMessage!!, color = Color(0xFF10B981))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    issuerInput = ""
                                    accountNameInput = ""
                                    secretInput = ""
                                    errorMessage = null
                                    successMessage = null
                                    currentScreen = Screen.ADD_ACCOUNT
                                }
                            ) {
                                Text("Add Account")
                            }
                            if (errorMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = errorMessage!!, color = Color(0xFFEF4444))
                            }
                        }

                        Screen.ADD_ACCOUNT -> {
                            val handleSaveAccount = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                if (issuerInput.isBlank() || accountNameInput.isBlank() || secretInput.isBlank()) {
                                    errorMessage = "All fields are required"
                                } else {
                                    val cleanSecret = secretInput.trim().uppercase()
                                    val base32Regex = Regex("^[A-Z2-7]+=*$")
                                    if (!cleanSecret.matches(base32Regex) || cleanSecret.length % 8 != 0) {
                                        errorMessage = "Invalid Base32 secret format or length"
                                    } else {
                                        val vault = activeVault
                                        if (vault == null) {
                                            errorMessage = "No active vault"
                                        } else {
                                            isLoading = true
                                            coroutineScope.launch(Dispatchers.IO) {
                                                try {
                                                    val tempVault = Vault(
                                                        VaultData(
                                                            version = vault.data.version,
                                                            entries = vault.data.entries.toMutableList()
                                                        )
                                                    )
                                                    tempVault.addEntry(
                                                        name = accountNameInput.trim(),
                                                        issuer = issuerInput.trim(),
                                                        secret = cleanSecret
                                                    )
                                                    val currentPass = VaultSecurityManager.getPassword(context) ?: passwordInput
                                                    tempVault.save(vaultFile, currentPass)
                                                    val reloaded = Vault.load(vaultFile, currentPass)
                                                    withContext(Dispatchers.Main) {
                                                        activeVault = reloaded
                                                        successMessage = "Account added successfully!"
                                                        errorMessage = null
                                                        currentScreen = Screen.MAIN
                                                        isLoading = false
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        errorMessage = e.message ?: "Failed to add account"
                                                        isLoading = false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Text(text = "Add TOTP Account", color = Color.White)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = issuerInput,
                                onValueChange = { issuerInput = it },
                                label = { Text("Issuer (e.g. GitHub)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF9CA3AF)
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = accountNameInput,
                                onValueChange = { accountNameInput = it },
                                label = { Text("Account Name") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF9CA3AF)
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = secretInput,
                                onValueChange = { secretInput = it },
                                label = { Text("Secret (Base32)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { handleSaveAccount() }
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
                                onClick = { handleSaveAccount() }
                            ) {
                                Text("Save Account")
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class Screen {
    SPLASH, SETUP, UNLOCK, MAIN, ADD_ACCOUNT
}
