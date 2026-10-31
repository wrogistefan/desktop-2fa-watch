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

    internal fun inspect(file: File, attributes: (Path) -> BasicFileAttributes): VaultPresence =
        inventory(file, attributes).presence

    internal fun inventory(file: File): VaultInventory = inventory(file) { path ->
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }

    private fun inventory(file: File, attributes: (Path) -> BasicFileAttributes): VaultInventory {
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
            val candidates = mutableListOf<RecoveryCandidate>()
            fun add(candidate: Path, label: String) {
                if (present(candidate)) candidates.add(RecoveryCandidate(candidate.toFile(), label))
            }
            add(path, "Current vault")
            add(path.resolveSibling("${file.name}.bak"), "Previous backup")
            add(path.resolveSibling("${file.name}.tmp"), "Pending write")
            // Older versions used random temp names. Their existence is not
            // permission to initialize, promote or discard an uncertain vault.
            val legacy = mutableListOf<Path>()
            Files.newDirectoryStream(path.parent, ".vault.*.tmp").use { stream ->
                for (candidate in stream) {
                    if (legacy.size >= 253) throw VaultIOException("Too many recovery candidates")
                    legacy.add(candidate)
                }
            }
            legacy.sortedBy { it.fileName.toString() }.forEachIndexed { index, candidate ->
                add(candidate, "Older pending write ${index + 1}")
            }
            return VaultInventory(file.absoluteFile, candidates)
        } catch (e: VaultException) {
            throw e
        } catch (e: Exception) {
            throw VaultIOException("Cannot determine vault storage state", e)
        }
    }
}
