package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.CancellationException
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultFiles
import org.desktop2fa.desktop.watch.vault.VaultPresence
import java.io.File

internal data class VaultStartup(
    val screen: Screen,
    val vault: Vault? = null,
    val errorMessage: String? = null,
)

/** Call off the UI thread. Only a positive absence result permits setup. */
internal fun resolveVaultStartup(
    file: File,
    readCachedPassword: () -> String?,
    inspect: (File) -> VaultPresence = VaultFiles::inspect,
    load: (File, String) -> Vault = Vault::load,
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
        VaultPresence.PRESENT -> {
            val loaded = try {
                readCachedPassword()?.let { load(file, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Cache, password, format and read failures never authorize setup.
                null
            }
            if (loaded != null) VaultStartup(Screen.MAIN, vault = loaded)
            else VaultStartup(Screen.UNLOCK)
        }
    }
}
