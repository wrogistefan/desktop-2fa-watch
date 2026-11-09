package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.CancellationException
import org.desktop2fa.desktop.watch.vault.VaultFiles
import org.desktop2fa.desktop.watch.vault.VaultPresence
import java.io.File

internal data class VaultStartup(
    val screen: Screen,
    val errorMessage: String? = null,
)

/** Call off the UI thread. Only a positive absence result permits setup. */
internal fun resolveVaultStartup(
    file: File,
    inspect: (File) -> VaultPresence = VaultFiles::inspect,
): VaultStartup {
    val presence = try {
        inspect(file)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return VaultStartup(Screen.STORAGE_ERROR, errorMessage =
            "Cannot check vault storage. Existing data has not been replaced.")
    }
    return when (presence) {
        VaultPresence.MISSING -> VaultStartup(Screen.SETUP)
        VaultPresence.RECOVERY_REQUIRED -> VaultStartup(Screen.STORAGE_ERROR, errorMessage =
            "Vault recovery files require review. New vault creation is disabled.")
        // Startup never authenticates or reads remembered credentials.
        VaultPresence.PRESENT -> VaultStartup(Screen.UNLOCK)
    }
}
