package org.desktop2fa.desktop.watch.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.Text
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultIOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.AccessDeniedException

class VaultStartupDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun withSyntheticFile(test: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-startup-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        try {
            test(File(directory, "synthetic.d2fa"))
        } finally {
            // Only files in this test's private disposable directory are touched.
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun freshSetupAndExistingEncryptedVaultTakeSeparateStartupPaths() = withSyntheticFile { file ->
        assertEquals(Screen.SETUP, resolveVaultStartup(file, { error("No cache on fresh setup") }).screen)
        val original = Vault.create().apply { addEntry("synthetic account", "synthetic service", "MY======") }
        original.saveNew(file, "synthetic password")
        val bytes = file.readBytes()
        assertEquals(Screen.UNLOCK, resolveVaultStartup(file, { null }).screen)
        val opened = resolveVaultStartup(file, { "synthetic password" })
        assertEquals(Screen.MAIN, opened.screen)
        assertEquals(original.entries, opened.vault!!.entries)
        assertEquals(Screen.UNLOCK, resolveVaultStartup(file, { "synthetic wrong password" }).screen)
        assertArrayEquals(bytes, file.readBytes())
        assertThrows(VaultIOException::class.java) { Vault.create().saveNew(file, "synthetic replacement") }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun backupAndUnsupportedFileNeverEnableInitialization() = withSyntheticFile { file ->
        val backup = File(file.parentFile, "${file.name}.bak").apply { writeText("synthetic recovery candidate") }
        val bytes = backup.readBytes()
        assertEquals(Screen.STORAGE_ERROR, resolveVaultStartup(file, { error("No cache for recovery") }).screen)
        assertFalse(file.exists())
        assertArrayEquals(bytes, backup.readBytes())
        file.writeText("synthetic unsupported or truncated input")
        val unsupported = file.readBytes()
        assertEquals(Screen.UNLOCK, resolveVaultStartup(file, { "synthetic password" }).screen)
        assertArrayEquals(unsupported, file.readBytes())
        assertArrayEquals(bytes, backup.readBytes())
    }

    @Test
    fun storageErrorOffersRetryAndOnlyConfirmedAbsenceThenAllowsSetup() = withSyntheticFile { file ->
        var denied = true
        fun check() = resolveVaultStartup(file, { error("No cache for missing/error") }, inspect = {
            if (denied) throw AccessDeniedException("synthetic path")
            org.desktop2fa.desktop.watch.vault.VaultFiles.inspect(it)
        })
        var startup by mutableStateOf(check())
        compose.setContent {
            if (startup.screen == Screen.STORAGE_ERROR) {
                StorageErrorScreen(startup.errorMessage, onRetry = { startup = check() })
            } else {
                // Isolated host: never open MainActivity or the real user's vault.
                Text("Startup route: ${startup.screen}")
            }
        }
        compose.onNodeWithText("Cannot check vault storage. Existing data has not been replaced.").assertIsDisplayed()
        compose.onNodeWithText("Create Vault").assertDoesNotExist()
        compose.onNodeWithText("Retry storage check").performClick()
        compose.onNodeWithText("Retry storage check").assertIsDisplayed()
        compose.runOnIdle { denied = false }
        compose.onNodeWithText("Retry storage check").performClick()
        compose.onNodeWithText("Startup route: SETUP").assertIsDisplayed()
        assertFalse(file.exists())
    }
}
