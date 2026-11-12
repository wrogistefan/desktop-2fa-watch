package org.desktop2fa.desktop.watch.vault

import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.model.VaultPayloadValidation
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Required separately from project-level approval; never restore consent from saved state. */
internal enum class VaultMigrationConsent { REFUSED, CONFIRMED }

internal data class VaultMigrationResult(val outcome: VaultSaveOutcome, val vault: Vault? = null) {
    val requiresReview: Boolean get() = outcome != VaultSaveOutcome.COMMITTED || vault == null
}

/** Inject file faults without changing KDF/encryption or silently bypassing authentication. */
internal open class VaultMigrationIO {
    open fun read(file: File): ByteArray = readVaultBytes(openVaultInput(file))
    open fun openArchive(file: File): VaultOutput = VaultReplacementIO().openTemp(file)
    open fun createArchive(file: File, bytes: ByteArray) {
        openArchive(file).use { output -> output.write(bytes); output.flush(); output.sync() }
    }
    open fun save(candidate: Vault, file: File, password: String): VaultSaveOutcome = candidate.save(file, password)
    open fun load(file: File, password: String): Vault = Vault.load(file, password)
    open fun inspect(file: File): VaultPresence = VaultFiles.inspect(file)
}

/**
 * Synchronous backend only. Caller must hold the session's process-wide writer mutex
 * and enter its non-cancellable, generation-owned mutation after explicit consent.
 * No app entry point invokes this until archive inventory/recovery and lifecycle UI
 * integration have their own tests. In particular, no automatic migration on unlock.
 */
internal fun migrateVaultToV2(
    file: File,
    noBackupDirectory: File,
    password: String,
    baseline: VaultData,
    consent: VaultMigrationConsent,
    io: VaultMigrationIO = VaultMigrationIO(),
): VaultMigrationResult {
    require(consent == VaultMigrationConsent.CONFIRMED) { "Explicit migration consent is required" }
    val expected = baseline.copy(entries = baseline.entries.toMutableList(),
        transferReceipts = baseline.transferReceipts.map { it.snapshot() })
    require(expected.version == 1) { "Migration requires a v1 baseline" }
    VaultPayloadValidation.requireValid(expected)
    if (io.inspect(file) != VaultPresence.PRESENT) throw VaultIOException("Migration requires clean existing storage")

    // Authenticate the exact bounded ciphertext that will be retained as the v1 copy.
    val original = io.read(file)
    val originalDigest = digest(original)
    try {
        val authenticated = Vault.load(file, password) { original.inputStream() }
        if (authenticated.data != expected) throw VaultIOException("Vault changed; review storage and renew migration consent")
        val candidate = Vault(expected.copy(version = 2))
        val archive = migrationArchive(noBackupDirectory)
        requireDirectory(noBackupDirectory)
        val archiveDirectory = requireNotNull(archive.parentFile)
        try { Files.createDirectory(archiveDirectory.toPath()) }
        catch (_: FileAlreadyExistsException) { requireDirectory(archiveDirectory) }
        // Confirmed absence only. A previous complete/partial archive is never overwritten.
        try {
            Files.readAttributes(archive.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            throw VaultIOException("Migration safety copy already exists; review storage")
        } catch (_: NoSuchFileException) { /* Positive absence, CREATE_NEW checks again. */ }
        requireUnchanged(file, original.size, originalDigest, io)
        io.createArchive(archive, original)
        val copy = io.read(archive)
        try {
            if (copy.size != original.size || !MessageDigest.isEqual(digest(copy), originalDigest)) {
                throw VaultIOException("Migration safety copy could not be verified")
            }
        } finally { copy.fill(0) }
        requireUnchanged(file, original.size, originalDigest, io)

        // After this point an exception is not proof of non-commit. Never roll back
        // from the safety copy or retry a save automatically.
        val reported = try { io.save(candidate, file, password) }
        catch (_: Exception) { VaultSaveOutcome.UNCERTAIN }
        val verified = try { io.load(file, password) } catch (_: Exception) { null }
        if (verified?.data == candidate.data) {
            val presence = try { io.inspect(file) } catch (_: Exception) { null }
            val outcome = when {
                presence == null || presence == VaultPresence.MISSING -> VaultSaveOutcome.UNCERTAIN
                presence == VaultPresence.RECOVERY_REQUIRED || reported == VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS ->
                    VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS
                else -> VaultSaveOutcome.COMMITTED
            }
            return VaultMigrationResult(outcome, verified.takeIf { outcome == VaultSaveOutcome.COMMITTED })
        }
        val knownCommit = reported == VaultSaveOutcome.COMMITTED ||
            reported == VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS
        if (!knownCommit && verified?.data == expected) {
            val current = try { io.read(file) } catch (_: Exception) { null }
            if (current != null) try {
                if (current.size == original.size && MessageDigest.isEqual(digest(current), originalDigest)) {
                    return VaultMigrationResult(VaultSaveOutcome.NOT_COMMITTED)
                }
            } finally { current.fill(0) }
        }
        return VaultMigrationResult(if (knownCommit) reported else VaultSaveOutcome.UNCERTAIN)
    } catch (failure: VaultException) {
        throw failure
    } catch (_: Exception) {
        throw VaultIOException("Migration preparation failed; original and recovery files were preserved")
    } finally { original.fill(0); originalDigest.fill(0) }
}

internal fun migrationArchive(noBackupDirectory: File): File =
    File(noBackupDirectory, "transfer-migration/vault-v1.d2fa")

private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun requireDirectory(directory: File) {
    val attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isDirectory) throw VaultIOException("Migration safety directory is unavailable")
}

private fun requireUnchanged(file: File, length: Int, expectedDigest: ByteArray, io: VaultMigrationIO) {
    val current = io.read(file)
    try {
        if (current.size != length || !MessageDigest.isEqual(digest(current), expectedDigest)) {
            throw VaultIOException("Vault changed; review storage and renew migration consent")
        }
    } finally { current.fill(0) }
}
