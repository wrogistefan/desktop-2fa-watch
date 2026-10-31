package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.CancellationException
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSaveOutcome
import java.io.File

internal data class AccountSaveResult(
    val outcome: VaultSaveOutcome,
    val vault: Vault? = null,
    val verificationFailed: Boolean = false,
) {
    val requiresReview: Boolean get() = outcome != VaultSaveOutcome.COMMITTED || verificationFailed
    val message: String get() = when {
        outcome == VaultSaveOutcome.NOT_COMMITTED -> "Account was not saved. Review storage before trying another addition."
        outcome == VaultSaveOutcome.UNCERTAIN -> "The save result is uncertain. Review existing data before adding this account again."
        verificationFailed -> "Account was saved, but could not be verified. Review existing data before adding it again."
        outcome == VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS -> "Account was saved. Recovery files need review before another addition."
        else -> "Account added successfully!"
    }
}

/** Call off the UI thread. Verification cannot change the already-known commit outcome. */
internal fun persistAccountCandidate(
    candidate: Vault,
    file: File,
    password: String,
    save: (Vault, File, String) -> VaultSaveOutcome = { vault, destination, supplied -> vault.save(destination, supplied) },
    load: (File, String) -> Vault = Vault::load,
): AccountSaveResult {
    val outcome = try {
        save(candidate, file, password)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // An unexpected writer exception is not proof that publication never happened.
        return AccountSaveResult(VaultSaveOutcome.UNCERTAIN)
    }
    if (outcome == VaultSaveOutcome.NOT_COMMITTED || outcome == VaultSaveOutcome.UNCERTAIN) {
        return AccountSaveResult(outcome)
    }
    val verified = try {
        load(file, password).takeIf { it.data == candidate.data }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    return AccountSaveResult(outcome, verified, verificationFailed = verified == null)
}
