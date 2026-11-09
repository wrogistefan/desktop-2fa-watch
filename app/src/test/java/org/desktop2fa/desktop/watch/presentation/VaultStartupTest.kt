package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.CancellationException
import org.desktop2fa.desktop.watch.vault.VaultPresence
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException

class VaultStartupTest {
    private val file = File("synthetic-unused.d2fa")

    @Test fun onlyConfirmedAbsenceAllowsSetup() {
        assertEquals(Screen.SETUP, resolveVaultStartup(file) { VaultPresence.MISSING }.screen)
    }

    @Test fun presentVaultAlwaysRequiresExplicitPasswordUnlock() {
        val result = resolveVaultStartup(file) { VaultPresence.PRESENT }
        assertEquals(Screen.UNLOCK, result.screen)
        assertNull(result.errorMessage)
    }

    @Test fun recoveryAndInspectionErrorsNeverAuthorizeSetupOrExposeDiagnostics() {
        assertEquals(Screen.STORAGE_ERROR, resolveVaultStartup(file) { VaultPresence.RECOVERY_REQUIRED }.screen)
        for (failure in listOf(AccessDeniedException("synthetic private detail"), IOException("synthetic private detail"), SecurityException())) {
            val result = resolveVaultStartup(file) { throw failure }
            assertEquals(Screen.STORAGE_ERROR, result.screen)
            assertFalse(result.errorMessage!!.contains("synthetic private detail"))
        }
    }

    @Test fun cancellationDoesNotPublishAnotherNavigationResult() {
        assertThrows(CancellationException::class.java) {
            resolveVaultStartup(file) { throw CancellationException() }
        }
    }
}
