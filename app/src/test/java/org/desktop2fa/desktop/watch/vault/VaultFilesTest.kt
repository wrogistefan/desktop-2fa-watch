package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer

class VaultFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun distinguishesMissingPresentAndRecoveryRequired() {
        val file = File(folder.root, "vault.d2fa")
        assertEquals(VaultPresence.MISSING, VaultFiles.inspect(file))
        val backup = File(folder.root, "vault.d2fa.bak")
        backup.writeText("synthetic recovery candidate")
        assertEquals(VaultPresence.RECOVERY_REQUIRED, VaultFiles.inspect(file))
        file.writeText("synthetic existing vault")
        assertEquals(VaultPresence.RECOVERY_REQUIRED, VaultFiles.inspect(file))
    }

    @Test
    fun inspectionErrorsAreNeverReportedAsMissing() {
        val file = folder.newFile("vault.d2fa")
        file.writeText("synthetic existing vault")
        val original = file.readBytes()
        for (failure in listOf(AccessDeniedException("synthetic"), IOException("synthetic"), SecurityException())) {
            assertThrows(VaultIOException::class.java) {
                VaultFiles.inspect(file) { path ->
                    if (path == file.toPath()) throw failure
                    Files.readAttributes(path, BasicFileAttributes::class.java)
                }
            }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun directoryAtVaultPathAndMissingParentRefuseSetup() {
        val directory = folder.newFolder("vault.d2fa")
        assertThrows(VaultIOException::class.java) { VaultFiles.inspect(directory) }
        assertThrows(VaultIOException::class.java) {
            VaultFiles.inspect(File(folder.root, "missing-parent/vault.d2fa"))
        }
    }

    @Test
    fun creationRefusesACompetingCreatorWithoutReplacingItsBytes() {
        val destination = File(folder.root, "vault.d2fa")
        assertEquals(VaultPresence.MISSING, VaultFiles.inspect(destination))
        destination.writeText("synthetic competing creator")
        val original = destination.readBytes()
        assertThrows(IOException::class.java) { VaultFiles.writeNew(destination, "synthetic candidate".toByteArray()) }
        assertArrayEquals(original, destination.readBytes())
    }

    @Test
    fun interruptedNewWriteLeavesAFileThatBlocksAnotherSetup() {
        val destination = File(folder.root, "vault.d2fa")
        assertThrows(IOException::class.java) {
            VaultFiles.writeNew(destination, "synthetic candidate".toByteArray()) { channel, bytes ->
                channel.write(ByteBuffer.wrap(bytes.copyOf(3)))
                throw IOException("synthetic write failure")
            }
        }
        val partial = destination.readBytes()
        assertEquals(3, partial.size)
        assertEquals(VaultPresence.PRESENT, VaultFiles.inspect(destination))
        assertThrows(VaultIOException::class.java) { Vault.create().saveNew(destination, "synthetic password") }
        assertArrayEquals(partial, destination.readBytes())
    }
}
