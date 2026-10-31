package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.CancellationException
import org.desktop2fa.desktop.watch.vault.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException

class VaultStartupTest {
    private val file = File("synthetic-unused.d2fa")
    private fun unexpectedCache(): String? = error("Cache must not be read")
    private fun unexpectedLoad(file: File, password: String): Vault = error("Load must not run")

    @Test
    fun onlyConfirmedAbsenceAllowsSetupWithoutConsultingCache() {
        val startup = resolveVaultStartup(file, ::unexpectedCache, { VaultPresence.MISSING }, ::unexpectedLoad)
        assertEquals(Screen.SETUP, startup.screen)
        assertNull(startup.vault)
        assertNull(startup.errorMessage)
    }

    @Test
    fun backupAndInspectionFailuresNeverAllowSetupOrExposeDiagnostics() {
        val backup = resolveVaultStartup(file, ::unexpectedCache, { VaultPresence.RECOVERY_REQUIRED }, ::unexpectedLoad)
        assertEquals(Screen.STORAGE_ERROR, backup.screen)
        assertNull(backup.vault)
        for (failure in listOf(AccessDeniedException("synthetic private detail"), IOException("synthetic private detail"), SecurityException())) {
            val startup = resolveVaultStartup(file, ::unexpectedCache, { throw failure }, ::unexpectedLoad)
            assertEquals(Screen.STORAGE_ERROR, startup.screen)
            assertNull(startup.vault)
            assertFalse(startup.errorMessage!!.contains("synthetic private detail"))
        }
    }

    @Test
    fun presentVaultWithoutCacheOrWithFailedCacheRequiresManualUnlock() {
        for (cache in listOf<() -> String?>({ null }, { throw IOException("synthetic cache failure") })) {
            val startup = resolveVaultStartup(file, cache, { VaultPresence.PRESENT }, ::unexpectedLoad)
            assertEquals(Screen.UNLOCK, startup.screen)
            assertNull(startup.vault)
        }
    }

    @Test
    fun authenticatedCachedLoadOpensOnlyTheReturnedVault() {
        val loaded = Vault.create()
        val startup = resolveVaultStartup(file, { "synthetic password" }, { VaultPresence.PRESENT }) { actualFile, password ->
            assertEquals(file, actualFile)
            assertEquals("synthetic password", password)
            loaded
        }
        assertEquals(Screen.MAIN, startup.screen)
        assertSame(loaded, startup.vault)
    }

    @Test
    fun allLoadFailuresKeepExistingVaultOnUnlockPath() {
        for (failure in listOf(
            InvalidPasswordException("synthetic"), CorruptedVaultException("synthetic"),
            UnsupportedFormatException("synthetic"), VaultIOException("synthetic"), IOException("synthetic"),
        )) {
            val startup = resolveVaultStartup(file, { "synthetic password" }, { VaultPresence.PRESENT }) { _, _ -> throw failure }
            assertEquals(Screen.UNLOCK, startup.screen)
            assertNull(startup.vault)
            assertNull(startup.errorMessage)
        }
    }

    @Test
    fun cancellationIsNotConvertedIntoSetupOrAnotherNavigationResult() {
        assertThrows(CancellationException::class.java) {
            resolveVaultStartup(file, ::unexpectedCache, { throw CancellationException() }, ::unexpectedLoad)
        }
        assertThrows(CancellationException::class.java) {
            resolveVaultStartup(file, { throw CancellationException() }, { VaultPresence.PRESENT }, ::unexpectedLoad)
        }
        assertThrows(CancellationException::class.java) {
            resolveVaultStartup(file, { "synthetic password" }, { VaultPresence.PRESENT }) { _, _ -> throw CancellationException() }
        }
    }
}
