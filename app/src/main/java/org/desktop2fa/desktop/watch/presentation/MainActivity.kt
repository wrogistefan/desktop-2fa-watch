package org.desktop2fa.desktop.watch.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import org.desktop2fa.desktop.watch.vault.Vault

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var activeVault by remember { mutableStateOf<Vault?>(null) }

            val importVaultLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    println("Selected: $uri")
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.padding(bottom = 32.dp),
                    text = "desktop-2fa",
                    color = Color.White
                )

                Button(
                    onClick = {
                        activeVault = Vault.create()
                    }
                ) {
                    Text(
                        text = "Initialize Vault",
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (activeVault == null) "No vault initialized" else "Vault active (entries: ${activeVault?.entries?.size})",
                    color = Color(0xFF3B82F6)
                )
            }
        }
    }
}
