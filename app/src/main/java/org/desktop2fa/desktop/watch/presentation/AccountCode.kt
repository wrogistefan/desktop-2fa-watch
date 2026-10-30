package org.desktop2fa.desktop.watch.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.Text
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.vault.TotpGenerationException
import org.desktop2fa.desktop.watch.vault.TotpGenerator

internal sealed interface AccountCodeState {
    data class Available(val code: String, val secondsRemaining: Long) : AccountCodeState
    data object Unavailable : AccountCodeState
}

internal fun accountCodeState(entry: TotpEntry, timeSeconds: Long): AccountCodeState = try {
    val code = TotpGenerator.generateTotp(entry.secret, timeSeconds, entry.period, entry.digits, entry.algorithm)
    AccountCodeState.Available(code, entry.period - (timeSeconds % entry.period))
} catch (_: TotpGenerationException) {
    AccountCodeState.Unavailable
}

@Composable
internal fun AccountCode(state: AccountCodeState) {
    when (state) {
        is AccountCodeState.Available -> {
            Text(text = state.code, color = Color.White)
            Text(text = "${state.secondsRemaining}s remaining", color = Color(0xFF9CA3AF))
        }
        AccountCodeState.Unavailable -> {
            Text(text = "Code unavailable", color = Color(0xFFFCA5A5))
            Text(text = "Check account data and watch time", color = Color(0xFF9CA3AF))
        }
    }
}
