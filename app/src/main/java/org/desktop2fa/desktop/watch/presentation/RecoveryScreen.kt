package org.desktop2fa.desktop.watch.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import org.desktop2fa.desktop.watch.vault.RecoveryCandidate

@Composable
internal fun RecoveryScreen(
    candidates: List<RecoveryCandidate>,
    password: String,
    error: String?,
    onPasswordChange: (String) -> Unit,
    onOpen: (RecoveryCandidate) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Recovery: read only")
        Text("Choose a file to view codes. No files will be replaced or removed. Adding accounts is disabled.")
        if (candidates.isEmpty()) Text("No recovery files available. Retry storage check.")
        else {
            OutlinedTextField(value = password, onValueChange = onPasswordChange,
                label = { Text("Vault password") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            candidates.forEach { candidate ->
                key(candidate.file.path) {
                    Button(onClick = { onOpen(candidate) }, enabled = password.isNotBlank()) { Text(candidate.label) }
                }
            }
        }
        error?.let { Text(it) }
        Button(onClick = onBack) { Text("Back to storage check") }
    }
}

@Composable
internal fun AddAccountAction(recoveryReadOnly: Boolean, onAdd: () -> Unit) {
    Button(enabled = !recoveryReadOnly, onClick = { if (!recoveryReadOnly) onAdd() }) { Text("Add Account") }
}
