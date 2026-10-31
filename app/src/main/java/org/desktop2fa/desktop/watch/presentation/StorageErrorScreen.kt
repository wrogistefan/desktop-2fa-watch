package org.desktop2fa.desktop.watch.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text

@Composable
internal fun StorageErrorScreen(message: String?, onRetry: () -> Unit, onRecovery: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message ?: "Vault storage is unavailable", color = Color.White)
        Button(onClick = onRetry) { Text("Retry storage check") }
        if (onRecovery != null) Button(onClick = onRecovery) { Text("Review recovery files") }
    }
}
