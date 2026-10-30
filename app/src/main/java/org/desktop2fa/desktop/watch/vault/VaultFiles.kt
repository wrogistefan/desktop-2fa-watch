package org.desktop2fa.desktop.watch.vault

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

enum class VaultPresence { PRESENT, MISSING, RECOVERY_REQUIRED }

/** Missing is a positive result of inspection, never the default for an I/O error. */
object VaultFiles {
    internal fun writeNew(
        destination: File,
        content: ByteArray,
        write: (FileChannel, ByteArray) -> Unit = { channel, bytes ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        },
    ) {
        // CREATE_NEW atomically checks absence and creates the file. Unlike a
        // check followed by rename, it cannot replace a competing destination.
        FileChannel.open(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            write(channel, content)
            channel.force(true)
        }
        // On failure leave the file in place: uncertain/partial data must never
        // authorize another setup or deletion of a possibly committed vault.
    }

    fun inspect(file: File): VaultPresence = inspect(file) { path ->
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }

    internal fun inspect(file: File, attributes: (Path) -> BasicFileAttributes): VaultPresence {
        try {
            val path = file.absoluteFile.toPath()
            val parent = attributes(requireNotNull(path.parent))
            if (!parent.isDirectory) throw VaultIOException("Vault directory is unavailable")
            fun present(candidate: Path): Boolean = try {
                if (!attributes(candidate).isRegularFile) {
                    throw VaultIOException("Vault path is not a regular file")
                }
                true
            } catch (_: NoSuchFileException) {
                false
            }
            return when {
                present(path) -> VaultPresence.PRESENT
                present(path.resolveSibling("${file.name}.bak")) -> VaultPresence.RECOVERY_REQUIRED
                else -> VaultPresence.MISSING
            }
        } catch (e: VaultException) {
            throw e
        } catch (e: Exception) {
            throw VaultIOException("Cannot determine vault storage state", e)
        }
    }
}
