package org.desktop2fa.desktop.watch.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.desktop2fa.desktop.watch.vault.RecoveryCandidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RecoveryScreenDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recoveryRequiresPasswordAndAChoiceAndDisablesAddingAccounts() {
        var password by mutableStateOf("")
        var selected: RecoveryCandidate? = null
        var addCalls = 0
        val candidate = RecoveryCandidate(File("synthetic-unused.d2fa.bak"), "Previous backup")
        var showingChoice by mutableStateOf(true)
        compose.setContent {
            if (showingChoice) RecoveryScreen(listOf(candidate), password, null,
                onPasswordChange = { password = it }, onOpen = { selected = it; showingChoice = false }, onBack = {})
            else Column { AddAccountAction(recoveryReadOnly = true, onAdd = { addCalls++ }) }
        }
        compose.onNodeWithText("Previous backup").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { password = "synthetic password" }
        compose.onNodeWithText("Previous backup").performClick()
        compose.runOnIdle { assertEquals(candidate, selected) }
        compose.onNodeWithText("Add Account").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, addCalls) }
    }
}
