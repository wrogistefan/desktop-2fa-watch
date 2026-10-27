package org.desktop2fa.desktop.watch.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
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

            var activeVault by remember { mutableStateOf<Vault?>(null) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var passwordInput by remember { mutableStateOf("") }
            val saveToKeystore = remember { true }

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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                                val res = PasswordStrengthValidator.evaluate(passwordInput)
                                if (res.isValid) {
                                    try {
                                        val newVault = Vault.create()
                                        newVault.save(vaultFile, passwordInput)
                                        if (saveToKeystore) {
                                            VaultSecurityManager.savePassword(context, passwordInput)
                                        }
                                        activeVault = newVault
                                        currentScreen = Screen.MAIN
                                        errorMessage = null
                                    } catch (e: Exception) {
                                        errorMessage = "Failed to create vault: ${e.message}"
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                                try {
                                    val loaded = Vault.load(vaultFile, passwordInput)
                                    if (saveToKeystore) {
                                        VaultSecurityManager.savePassword(context, passwordInput)
                                    }
                                    activeVault = loaded
                                    currentScreen = Screen.MAIN
                                    errorMessage = null
                                } catch (_: Exception) {
                                    errorMessage = "Invalid password"
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                issuerInput = ""
                                accountNameInput = ""
                                secretInput = ""
                                errorMessage = null
                                currentScreen = Screen.ADD_ACCOUNT
                            }
                        ) {
                            Text("Add Account")
                        }
                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = errorMessage!!, color = Color(0xFF3B82F6))
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
                                try {
                                    activeVault?.addEntry(
                                        name = accountNameInput.trim(),
                                        issuer = issuerInput.trim(),
                                        secret = secretInput.trim().uppercase()
                                    )
                                    val currentPass = VaultSecurityManager.getPassword(context) ?: passwordInput
                                    activeVault?.save(vaultFile, currentPass)
                                    activeVault = Vault.load(vaultFile, currentPass)
                                    currentScreen = Screen.MAIN
                                    errorMessage = "Account added successfully!"
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Failed to add account"
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

enum class Screen {
    SETUP, UNLOCK, MAIN, ADD_ACCOUNT
}
