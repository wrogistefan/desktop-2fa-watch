package org.desktop2fa.desktop.watch.vault

import java.io.File

internal data class RecoveryCandidate(val file: File, val label: String)

internal data class VaultInventory(val main: File, val candidates: List<RecoveryCandidate>) {
    val presence: VaultPresence get() = when {
        candidates.any { it.file != main } -> VaultPresence.RECOVERY_REQUIRED
        candidates.isNotEmpty() -> VaultPresence.PRESENT
        else -> VaultPresence.MISSING
    }
}

/** A recovery view grants access to codes, never permission to save or clean up. */
internal data class RecoveryView(val vault: Vault, val sourceLabel: String)

internal fun openRecoveryView(
    main: File, candidate: RecoveryCandidate, password: String,
    load: (File, String) -> Vault = Vault::load,
): RecoveryView {
    val current = VaultFiles.inventory(main).candidates.firstOrNull { it.file == candidate.file.absoluteFile }
        ?: throw VaultIOException("Recovery candidate is no longer available")
    // Authentication, versions, account validation and bounded reads are identical
    // to normal loads. An inventory entry alone never establishes usable data.
    return RecoveryView(load(current.file, password), current.label)
}
