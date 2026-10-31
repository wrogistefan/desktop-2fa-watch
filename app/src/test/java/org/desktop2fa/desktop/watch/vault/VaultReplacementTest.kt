package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException

class VaultReplacementTest {
    @get:Rule val folder = TemporaryFolder()
    private val oldBytes get() = javaClass.getResourceAsStream("/desktop-v0.8.3.bin")!!.use { it.readBytes() }
    private fun candidate() = newBytes

    companion object {
        private val newBytes by lazy {
            val salt = CryptoUtils.generateSalt(16)
            val key = CryptoUtils.deriveKey("synthetic update password", salt)
            val payload = """{"version":1,"entries":[{"secret":"MY======","account_name":"synthetic new account"}]}"""
            Vault.VAULT_MAGIC + Vault.VAULT_VERSION + salt + CryptoUtils.encrypt(key, payload.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun precommitFaultMatrixPreservesTheOriginalAndClosesOpenedOutput() {
        for (stage in listOf("create", "write", "flush", "sync", "close", "backup", "backupSync", "move")) {
            val directory = folder.newFolder(stage)
            val destination = File(directory, "vault.d2fa").apply { writeBytes(oldBytes) }
            val original = destination.readBytes()
            val io = FaultIO(stage)
            val failure = assertThrows(VaultIOException::class.java) {
                replaceVaultFile(destination, candidate(), io)
            }
            assertArrayEquals(original, destination.readBytes())
            assertEquals(0, io.cleanupCalls)
            assertFalse(failure.message!!.contains("synthetic diagnostic"))
            assertEquals(stage == "move", (failure as VaultReplacementFailure).mayHaveCommitted)
            if (stage != "create") assertTrue("Output closed at $stage", io.closed)
            if (stage in listOf("backupSync", "move")) {
                assertArrayEquals(original, File(directory, "vault.d2fa.bak").readBytes())
            }
        }
    }

    @Test
    fun unsupportedAtomicMoveNeverAttemptsANonAtomicFallbackOrRestore() {
        val destination = folder.newFile("vault.d2fa").apply { writeBytes(oldBytes) }
        var calls = 0
        val io = object : VaultReplacementIO() {
            override fun replace(temp: File, destination: File) {
                calls++
                throw AtomicMoveNotSupportedException(temp.path, destination.path, "synthetic unsupported")
            }
        }
        assertThrows(VaultIOException::class.java) { replaceVaultFile(destination, candidate(), io) }
        assertEquals(1, calls)
        assertArrayEquals(oldBytes, destination.readBytes())
        assertArrayEquals(oldBytes, File(folder.root, "vault.d2fa.bak").readBytes())
        assertArrayEquals(candidate(), File(folder.root, "vault.d2fa.tmp").readBytes())
    }

    @Test
    fun uncertainExceptionAfterReplacementNeverRestoresOlderDataOrDeletesEvidence() {
        val destination = folder.newFile("vault.d2fa").apply { writeBytes(oldBytes) }
        val io = object : VaultReplacementIO() {
            override fun replace(temp: File, destination: File) {
                super.replace(temp, destination)
                throw IOException("synthetic failure after publication")
            }
            override fun deleteBackup(backup: File) = error("No destructive cleanup on uncertainty")
        }
        assertThrows(VaultIOException::class.java) { replaceVaultFile(destination, candidate(), io) }
        assertArrayEquals(candidate(), destination.readBytes())
        assertArrayEquals(oldBytes, File(folder.root, "vault.d2fa.bak").readBytes())
    }

    @Test
    fun successfulAtomicReplacementPublishesCandidateAndRemovesBackup() {
        val destination = folder.newFile("vault.d2fa").apply { writeBytes(oldBytes) }
        assertEquals(VaultReplacementResult.COMMITTED, replaceVaultFile(destination, candidate()))
        assertArrayEquals(candidate(), destination.readBytes())
        assertEquals("synthetic new account", Vault.load(destination, "synthetic update password").entries.single().accountName)
        assertFalse(File(folder.root, "vault.d2fa.tmp").exists())
        assertFalse(File(folder.root, "vault.d2fa.bak").exists())
    }

    @Test
    fun cleanupFailureLeavesCommittedDataAndReportsRemainingRecoveryArtifact() {
        val destination = folder.newFile("vault.d2fa").apply { writeBytes(oldBytes) }
        val io = object : VaultReplacementIO() {
            override fun deleteBackup(backup: File) { throw IOException("synthetic cleanup failure") }
        }
        assertEquals(VaultReplacementResult.COMMITTED_WITH_RECOVERY_ARTIFACTS,
            replaceVaultFile(destination, candidate(), io))
        assertArrayEquals(candidate(), destination.readBytes())
        assertArrayEquals(oldBytes, File(folder.root, "vault.d2fa.bak").readBytes())
        val committed = destination.readBytes()
        assertThrows(VaultIOException::class.java) { replaceVaultFile(destination, oldBytes) }
        assertArrayEquals(committed, destination.readBytes())
        assertArrayEquals(oldBytes, File(folder.root, "vault.d2fa.bak").readBytes())
    }

    @Test
    fun existingTempAndBackupCandidatesCannotBeOverwritten() {
        for (suffix in listOf("tmp", "bak")) {
            val directory = folder.newFolder(suffix)
            val destination = File(directory, "vault.d2fa").apply { writeBytes(oldBytes) }
            val artifact = File(directory, "vault.d2fa.$suffix").apply { writeText("synthetic ambiguous candidate") }
            val bytes = artifact.readBytes()
            assertThrows(VaultIOException::class.java) { replaceVaultFile(destination, candidate()) }
            assertArrayEquals(oldBytes, destination.readBytes())
            assertArrayEquals(bytes, artifact.readBytes())
        }
    }

    @Test
    fun updateRequiresAnExistingRegularFileAndNeverBecomesInitialization() {
        val missing = File(folder.root, "missing.d2fa")
        assertThrows(VaultIOException::class.java) { replaceVaultFile(missing, candidate()) }
        assertFalse(missing.exists())
        val directory = folder.newFolder("directory.d2fa")
        assertThrows(VaultIOException::class.java) { replaceVaultFile(directory, candidate()) }
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    private class FaultIO(private val stage: String) : VaultReplacementIO() {
        var closed = false
        var cleanupCalls = 0
        private fun fail(at: String) { if (stage == at) throw IOException("synthetic diagnostic: $at") }
        override fun openTemp(file: File): VaultOutput {
            fail("create")
            val actual = super.openTemp(file)
            return object : VaultOutput {
                override fun write(bytes: ByteArray) {
                    if (stage == "write") { actual.write(bytes.copyOf(3)); fail("write") }
                    actual.write(bytes)
                }
                override fun flush() { fail("flush"); actual.flush() }
                override fun sync() { fail("sync"); actual.sync() }
                override fun close() { closed = true; actual.close(); fail("close") }
            }
        }
        override fun backup(source: File, backup: File) {
            if (stage == "backup") { backup.writeBytes(source.readBytes().copyOf(3)); fail("backup") }
            super.backup(source, backup)
        }
        override fun syncBackup(backup: File) { fail("backupSync"); super.syncBackup(backup) }
        override fun replace(temp: File, destination: File) { fail("move"); super.replace(temp, destination) }
        override fun deleteBackup(backup: File) { cleanupCalls++; super.deleteBackup(backup) }
    }
}
