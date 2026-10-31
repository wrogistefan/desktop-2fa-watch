package org.desktop2fa.desktop.watch.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSaveOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

class AccountSaveOutcomeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun committedWriteWithFailedReloadOffersStorageReviewWithoutReaddition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-save-outcome-${System.nanoTime()}").apply { check(mkdir()) }
        val file = File(directory, "synthetic.d2fa")
        val password = "synthetic outcome password"
        try {
            Vault.create().saveNew(file, password)
            val candidate = Vault.create().apply { addEntry("synthetic account", "synthetic service", "MY======") }
            val result = persistAccountCandidate(candidate, file, password,
                load = { _, _ -> throw IOException("synthetic post-commit read failure") })
            assertEquals(VaultSaveOutcome.COMMITTED, result.outcome)
            assertTrue(result.requiresReview)
            assertTrue(result.verificationFailed)
            assertEquals(candidate.entries, Vault.load(file, password).entries)
            val committed = file.readBytes()
            compose.setContent { StorageErrorScreen(result.message, onRetry = {}, onRecovery = {}) }
            compose.onNodeWithText(result.message).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Review recovery files").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("Add Account").assertCountEquals(0)
            compose.onAllNodesWithText("Try again").assertCountEquals(0)
            assertArrayEquals(committed, file.readBytes())
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
