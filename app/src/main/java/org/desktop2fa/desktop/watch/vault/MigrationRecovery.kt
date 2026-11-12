package org.desktop2fa.desktop.watch.vault

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A retained safety copy is not a pending write and must not block a healthy main. */
internal class MigrationAwareInventory(
    private val ordinary: VaultInventory,
    private val migrationDirectoryPresent: Boolean,
    private val safetyCopy: RecoveryCandidate?,
) {
    val candidates: List<RecoveryCandidate> get() = ordinary.candidates.toList() + listOfNotNull(safetyCopy)
    val presence: VaultPresence get() = when {
        ordinary.presence != VaultPresence.MISSING -> ordinary.presence
        migrationDirectoryPresent -> VaultPresence.RECOVERY_REQUIRED
        else -> VaultPresence.MISSING
    }
}

/** Metadata only. No decryption, file promotion, deletion or creation. Missing must be positive. */
internal fun migrationAwareInventory(
    main: File, noBackupDirectory: File,
    attributes: (Path) -> BasicFileAttributes = { path ->
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    },
): MigrationAwareInventory {
    val ordinary = VaultFiles.inventory(main)
    try {
        val root = noBackupDirectory.absoluteFile.toPath().normalize()
        val rootAttributes = try {
            attributes(root)
        } catch (_: NoSuchFileException) { return MigrationAwareInventory(ordinary, false, null) }
        if (!rootAttributes.isDirectory) throw VaultIOException("Safety-copy directory is unavailable")
        val archive = migrationArchive(root.toFile()).toPath()
        val directory = requireNotNull(archive.parent)
        val directoryAttributes = try {
            attributes(directory)
        } catch (_: NoSuchFileException) { return MigrationAwareInventory(ordinary, false, null) }
        if (!directoryAttributes.isDirectory) throw VaultIOException("Safety-copy path requires review")
        val attributes = try {
            attributes(archive)
        } catch (_: NoSuchFileException) { return MigrationAwareInventory(ordinary, true, null) }
        if (!attributes.isRegularFile) throw VaultIOException("Safety copy is not a regular file")
        val candidate = RecoveryCandidate(archive.toFile(),
            "Before transfer migration (read-only; may omit newer accounts)", RecoveryFingerprint.from(attributes))
        return MigrationAwareInventory(ordinary, true, candidate)
    } catch (failure: VaultException) { throw failure }
    catch (_: Exception) { throw VaultIOException("Cannot inspect migration recovery storage") }
}

/** Authenticate only an explicitly selected source. Never restore over main or enable saving. */
internal fun openMigrationAwareRecoveryView(
    main: File, noBackupDirectory: File, candidate: RecoveryCandidate, password: String,
    load: (File, String) -> Vault = Vault::load,
): RecoveryView {
    val path = candidate.file.absoluteFile.toPath().normalize().toFile()
    val before = migrationAwareInventory(main, noBackupDirectory).candidates.firstOrNull { it.file == path }
        ?: throw VaultIOException("Recovery candidate is no longer available")
    if (candidate.fingerprint == null || before.fingerprint != candidate.fingerprint)
        throw VaultIOException("Recovery candidate changed; refresh the selection")
    if (path != migrationArchive(noBackupDirectory.absoluteFile.toPath().normalize().toFile()))
        return openRecoveryView(main, before, password, load)
    val vault = load(before.file, password)
    if (vault.data.version != 1) throw UnsupportedFormatException("Migration safety copy must be v1")
    val after = migrationAwareInventory(main, noBackupDirectory).candidates.firstOrNull { it.file == path }
    if (after?.fingerprint != before.fingerprint) throw VaultIOException("Recovery candidate changed while opening")
    return RecoveryView(vault, before.label)
}
