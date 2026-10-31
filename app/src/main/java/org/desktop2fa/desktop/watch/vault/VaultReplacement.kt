package org.desktop2fa.desktop.watch.vault

import java.io.BufferedOutputStream
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal interface VaultOutput : AutoCloseable {
    fun write(bytes: ByteArray)
    fun flush()
    fun sync()
}

/** Small filesystem boundary for deterministic write/flush/sync/publication faults. */
internal open class VaultReplacementIO {
    open fun openTemp(file: File): VaultOutput {
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val output = BufferedOutputStream(Channels.newOutputStream(channel))
        return object : VaultOutput {
            override fun write(bytes: ByteArray) = output.write(bytes)
            override fun flush() = output.flush()
            override fun sync() = channel.force(true)
            override fun close() = output.close()
        }
    }

    open fun backup(source: File, backup: File) {
        // Never replace an ambiguous recovery candidate from a previous attempt.
        Files.copy(source.toPath(), backup.toPath())
    }

    open fun syncBackup(backup: File) {
        FileChannel.open(backup.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    open fun replace(temp: File, destination: File) {
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    open fun deleteBackup(backup: File) { Files.delete(backup.toPath()) }
}

internal enum class VaultReplacementResult { COMMITTED, COMMITTED_WITH_RECOVERY_ARTIFACTS }

/** Existing-file updates only. Failure never authorizes destructive cleanup/restore. */
internal fun replaceVaultFile(
    destination: File,
    content: ByteArray,
    io: VaultReplacementIO = VaultReplacementIO(),
): VaultReplacementResult {
    if (VaultFiles.inspect(destination) != VaultPresence.PRESENT) {
        throw VaultIOException("An existing vault is required for an update")
    }
    val absolute = destination.absoluteFile
    val temp = File(absolute.parentFile, "${absolute.name}.tmp")
    val backup = File(absolute.parentFile, "${absolute.name}.bak")
    try {
        io.openTemp(temp).use { output ->
            output.write(content)
            output.flush()
            output.sync()
        }
        io.backup(absolute, backup)
        io.syncBackup(backup)
        // No non-atomic fallback, even for AtomicMoveNotSupportedException.
        // A failed move may have an uncertain outcome; never copy an older
        // backup over a potentially committed destination or erase evidence.
        io.replace(temp, absolute)
    } catch (failure: Exception) {
        throw VaultIOException("Vault update failed; recovery files have been preserved", failure)
    }
    // A successful move is the commit point. Cleanup cannot roll it back or
    // turn it into a failed write. UI-facing outcomes are handled separately.
    return try {
        io.deleteBackup(backup)
        VaultReplacementResult.COMMITTED
    } catch (_: Exception) {
        VaultReplacementResult.COMMITTED_WITH_RECOVERY_ARTIFACTS
    }
}
