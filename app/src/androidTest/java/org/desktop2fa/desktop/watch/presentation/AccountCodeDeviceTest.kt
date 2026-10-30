package org.desktop2fa.desktop.watch.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.junit.Rule
import org.junit.Test

class AccountCodeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unavailableEntryShowsFeedbackBesideAValidSyntheticCodeWithoutFallback() {
        val valid = TotpEntry(secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", digits = 8)
        compose.setContent {
            Column {
                AccountCode(accountCodeState(valid.copy(period = 0), 59))
                AccountCode(accountCodeState(valid, 59))
            }
        }
        compose.onNodeWithText("Code unavailable").assertIsDisplayed()
        compose.onNodeWithText("Check account data and watch time").assertIsDisplayed()
        compose.onNodeWithText("94287082").assertIsDisplayed()
        compose.onNodeWithText("1s remaining").assertIsDisplayed()
        compose.onAllNodesWithText("s remaining", substring = true).assertCountEquals(1)
        compose.onNodeWithText("------").assertDoesNotExist()
    }
}
