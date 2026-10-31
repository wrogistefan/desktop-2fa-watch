package org.desktop2fa.desktop.watch.vault

import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Ephemeral change detection, never a persistent vault/account identifier. */
internal data class RecoveryFingerprint(
    val size: Long, val modified: FileTime, val created: FileTime, val fileKey: Any?,
) {
    companion object {
        fun from(attributes: BasicFileAttributes) = RecoveryFingerprint(
            attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey(),
        )
    }
}

internal data class RecoveryCandidate(
    val file: File, val label: String, val fingerprint: RecoveryFingerprint? = null,
)

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
    val selectedPath = candidate.file.absoluteFile.toPath().normalize().toFile()
    val current = VaultFiles.inventory(main).candidates.firstOrNull { it.file == selectedPath }
        ?: throw VaultIOException("Recovery candidate is no longer available")
    if (candidate.fingerprint == null || current.fingerprint != candidate.fingerprint) {
        throw VaultIOException("Recovery candidate changed; refresh the selection")
    }
    // Authentication, versions, account validation and bounded reads are identical
    // to normal loads. An inventory entry alone never establishes usable data.
    val vault = load(current.file, password)
    val after = VaultFiles.inventory(main).candidates.firstOrNull { it.file == selectedPath }
    if (after?.fingerprint != current.fingerprint) {
        throw VaultIOException("Recovery candidate changed while opening")
    }
    return RecoveryView(vault, current.label)
}
