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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.desktop2fa.desktop.watch.vault.PasswordStrengthValidator
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = applicationContext
            val vaultFile = remember { File(context.filesDir, "vault.d2fa") }
            val coroutineScope = rememberCoroutineScope()

            var activeVault by remember { mutableStateOf<Vault?>(null) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var successMessage by remember { mutableStateOf<String?>(null) }
            var passwordInput by remember { mutableStateOf("") }
            var saveToKeystore by remember { mutableStateOf(true) }
            var isLoading by remember { mutableStateOf(false) }

            // Add Account inputs
            var issuerInput by remember { mutableStateOf("") }
            var accountNameInput by remember { mutableStateOf("") }
            var secretInput by remember { mutableStateOf("") }

            val vaultExists = remember { vaultFile.exists() }
            var currentScreen by remember {
                mutableStateOf(
                    if (!vaultExists) Screen.SETUP else {
                        val cachedPass = VaultSecurityManager.getPassword(context)
                        if (cachedPass != null) {
                            try {
                                activeVault = Vault.load(vaultFile, cachedPass)
                                Screen.MAIN
                            } catch (_: Exception) {
                                Screen.UNLOCK
                            }
                        } else {
                            Screen.UNLOCK
                        }
                    }
                )
            }

            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A))
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading) {
                    Text(text = "Processing...", color = Color(0xFF3B82F6))
                } else {
                    when (currentScreen) {
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
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done
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
                                onClick = {
                                    if (passwordInput.isBlank()) {
                                        errorMessage = "Password cannot be empty"
                                        return@Button
                                    }
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
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done
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
                                onClick = {
                                    if (passwordInput.isBlank()) {
                                        errorMessage = "Password cannot be empty"
                                        return@Button
                                    }
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
                                        } catch (_: Exception) {
                                            withContext(Dispatchers.Main) {
                                                errorMessage = "Invalid password"
                                                isLoading = false
                                            }
                                        }
                                    }
                                }
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
                            Text(text = "Add TOTP Account", color = Color.White)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = issuerInput,
                                onValueChange = { issuerInput = it },
                                label = { Text("Issuer (e.g. GitHub)") },
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
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done
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
                                onClick = {
                                    if (issuerInput.isBlank() || accountNameInput.isBlank() || secretInput.isBlank()) {
                                        errorMessage = "All fields are required"
                                        return@Button
                                    }
                                    val cleanSecret = secretInput.trim().uppercase()
                                    val base32Regex = Regex("^[A-Z2-7]+=*$")
                                    if (!cleanSecret.matches(base32Regex) || cleanSecret.length % 8 != 0) {
                                        errorMessage = "Invalid Base32 secret format or length"
                                        return@Button
                                    }
                                    val vault = activeVault
                                    if (vault == null) {
                                        errorMessage = "No active vault"
                                        return@Button
                                    }
                                    isLoading = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            vault.addEntry(
                                                name = accountNameInput.trim(),
                                                issuer = issuerInput.trim(),
                                                secret = cleanSecret
                                            )
                                            val currentPass = VaultSecurityManager.getPassword(context) ?: passwordInput
                                            vault.save(vaultFile, currentPass)
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
    SETUP, UNLOCK, MAIN, ADD_ACCOUNT
}
