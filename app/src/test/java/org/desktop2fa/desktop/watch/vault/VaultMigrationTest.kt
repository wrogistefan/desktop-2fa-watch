package org.desktop2fa.desktop.watch.vault

import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.model.VaultData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Synthetic encrypted vaults only; the app has no migration entry point yet. */
class VaultMigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "synthetic migration password"
    private val baseline = VaultData(entries = mutableListOf(
        TotpEntry("Syntetyczny Żółw", null, " m\ty\n ", 7, 45, "SHA256"),
        TotpEntry(null, "same", "MY======"), TotpEntry(null, "same", "my"),
    ))
    private val consent = VaultMigrationConsent.CONFIRMED
    private fun source(): File = File(folder.newFolder(), "vault.d2fa").also {
        Vault(baseline.copy(entries = baseline.entries.toMutableList())).saveNew(it, password)
    }
    private fun noBackup() = folder.newFolder()

    @Test fun cleanMigrationRetainsExactEncryptedV1AndEveryAccountField() {
        val file = source()
        val old = file.readBytes()
        val root = noBackup()
        val result = migrateVaultToV2(file, root, password, baseline, consent)
        assertFalse(result.requiresReview)
        assertEquals(VaultSaveOutcome.COMMITTED, result.outcome)
        assertEquals(baseline.copy(version = 2), result.vault!!.data)
        assertEquals(baseline.copy(version = 2), Vault.load(file, password).data)
        val copy = migrationArchive(root)
        assertArrayEquals(old, copy.readBytes())
        assertEquals(baseline, Vault.load(copy, password).data)
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
        assertEquals("transfer-migration", copy.parentFile!!.name)
        assertEquals("vault-v1.d2fa", copy.name)
    }

    @Test fun refusedConsentPerformsNoIoOrDirectoryCreation() {
        val file = source()
        val old = file.readBytes()
        val root = noBackup()
        val io = object : VaultMigrationIO() {
            override fun inspect(file: File): VaultPresence = error("No IO before consent")
        }
        assertThrows(IllegalArgumentException::class.java) {
            migrateVaultToV2(file, root, password, baseline, VaultMigrationConsent.REFUSED, io)
        }
        assertArrayEquals(old, file.readBytes())
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test fun wrongPasswordStaleSnapshotAndV2BaselineNeverCreateAnArchive() {
        val file = source()
        val old = file.readBytes()
        val root = noBackup()
        assertThrows(InvalidPasswordException::class.java) {
            migrateVaultToV2(file, root, "synthetic wrong", baseline, consent)
        }
        assertThrows(VaultIOException::class.java) {
            migrateVaultToV2(file, root, password, baseline.copy(entries = mutableListOf()), consent)
        }
        assertThrows(IllegalArgumentException::class.java) {
            migrateVaultToV2(file, root, password, baseline.copy(version = 2), consent)
        }
        assertArrayEquals(old, file.readBytes())
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test fun existingPartialCopyAndPendingStorageAreNeverOverwritten() {
        val file = source()
        val old = file.readBytes()
        val root = noBackup()
        val archive = migrationArchive(root)
        archive.parentFile!!.mkdirs()
        val partial = byteArrayOf(1, 2, 3)
        archive.writeBytes(partial)
        assertThrows(VaultIOException::class.java) { migrateVaultToV2(file, root, password, baseline, consent) }
        assertArrayEquals(old, file.readBytes())
        assertArrayEquals(partial, archive.readBytes())
        for (suffix in listOf("tmp", "bak")) {
            val another = source()
            val artifact = File(another.parentFile, "vault.d2fa.$suffix").apply { writeBytes(partial) }
            val otherRoot = noBackup()
            val original = another.readBytes()
            assertThrows(VaultIOException::class.java) {
                migrateVaultToV2(another, otherRoot, password, baseline, consent)
            }
            assertArrayEquals(original, another.readBytes())
            assertArrayEquals(partial, artifact.readBytes())
            assertEquals(emptyList<String>(), otherRoot.list()!!.toList())
        }
    }

    @Test fun archiveCreateWriteFlushSyncAndCloseFailuresPreserveTheSource() {
        for (stage in listOf("create", "write", "flush", "sync", "close")) {
            val file = source()
            val old = file.readBytes()
            val root = noBackup()
            var closed = false
            val io = object : VaultMigrationIO() {
                fun fail(at: String) { if (stage == at) throw IOException("synthetic copy failure") }
                override fun openArchive(file: File): VaultOutput {
                    fail("create")
                    val actual = super.openArchive(file)
                    return object : VaultOutput {
                        override fun write(bytes: ByteArray) {
                            if (stage == "write") { actual.write(bytes.copyOf(7)); fail("write") }
                            actual.write(bytes)
                        }
                        override fun flush() { fail("flush"); actual.flush() }
                        override fun sync() { fail("sync"); actual.sync() }
                        override fun close() { actual.close(); closed = true; fail("close") }
                    }
                }
                override fun save(candidate: Vault, file: File, password: String): VaultSaveOutcome =
                    error("No publication after a copy failure")
            }
            assertThrows(VaultIOException::class.java) { migrateVaultToV2(file, root, password, baseline, consent, io) }
            assertArrayEquals(old, file.readBytes())
            assertEquals(stage != "create", closed)
            assertEquals(stage != "create", migrationArchive(root).exists())
            assertFalse(File(file.parentFile, "vault.d2fa.tmp").exists())
        }
    }

    @Test fun aCorruptedCopyNeverAuthorizesPublicationOrAutomaticCleanup() {
        val file = source()
        val old = file.readBytes()
        val root = noBackup()
        val io = object : VaultMigrationIO() {
            override fun createArchive(file: File, bytes: ByteArray) {
                super.createArchive(file, bytes)
                val corrupted = file.readBytes()
                corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
                file.writeBytes(corrupted)
            }
            override fun save(candidate: Vault, file: File, password: String): VaultSaveOutcome =
                error("Copy verification must precede publication")
        }
        assertThrows(VaultIOException::class.java) { migrateVaultToV2(file, root, password, baseline, consent, io) }
        assertArrayEquals(old, file.readBytes())
        assertTrue(migrationArchive(root).exists())
        assertFalse(old.contentEquals(migrationArchive(root).readBytes()))
    }

    @Test fun changedSourceAfterCopyRequiresNewConsentAndPreservesTheOtherWrite() {
        val main = source()
        val old = main.readBytes()
        val root = noBackup()
        val changed = baseline.copy(entries = (baseline.entries + TotpEntry(secret = "MY")).toMutableList())
        var changedBytes = byteArrayOf()
        val io = object : VaultMigrationIO() {
            override fun createArchive(file: File, bytes: ByteArray) {
                super.createArchive(file, bytes)
                assertEquals(VaultSaveOutcome.COMMITTED, Vault(changed).save(main, password))
                changedBytes = main.readBytes()
            }
        }
        assertThrows(VaultIOException::class.java) { migrateVaultToV2(main, root, password, baseline, consent, io) }
        assertArrayEquals(changedBytes, main.readBytes())
        assertEquals(changed, Vault.load(main, password).data)
        assertArrayEquals(old, migrationArchive(root).readBytes())
    }

    @Test fun uncertaintyBeforeAndAfterPublicationIsResolvedByAuthenticatedReread() {
        for (afterMove in listOf(false, true)) {
            val file = source()
            val old = file.readBytes()
            val root = noBackup()
            val io = object : VaultMigrationIO() {
                override fun save(candidate: Vault, file: File, password: String): VaultSaveOutcome =
                    candidate.save(file, password, object : VaultReplacementIO() {
                        override fun replace(temp: File, destination: File) {
                            if (afterMove) super.replace(temp, destination)
                            throw IOException("synthetic move uncertainty")
                        }
                    })
            }
            val result = migrateVaultToV2(file, root, password, baseline, consent, io)
            assertEquals(if (afterMove) VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS
                else VaultSaveOutcome.NOT_COMMITTED, result.outcome)
            assertTrue(result.requiresReview)
            assertNull(result.vault)
            assertEquals(baseline.copy(version = if (afterMove) 2 else 1), Vault.load(file, password).data)
            assertArrayEquals(old, migrationArchive(root).readBytes())
            assertArrayEquals(old, File(file.parentFile, "vault.d2fa.bak").readBytes())
        }
    }

    @Test fun anExceptionAfterCleanPublicationCanStillProduceAVerifiedCommit() {
        val file = source()
        val root = noBackup()
        val io = object : VaultMigrationIO() {
            override fun save(candidate: Vault, file: File, password: String): VaultSaveOutcome {
                assertEquals(VaultSaveOutcome.COMMITTED, super.save(candidate, file, password))
                throw IOException("synthetic late exception")
            }
        }
        val result = migrateVaultToV2(file, root, password, baseline, consent, io)
        assertFalse(result.requiresReview)
        assertEquals(baseline.copy(version = 2), result.vault!!.data)
        assertEquals(baseline, Vault.load(migrationArchive(root), password).data)
    }

    @Test fun unreadableVerificationCannotAuthorizeRollbackOrRetry() {
        for (reportedCommit in listOf(false, true)) {
            val file = source()
            val old = file.readBytes()
            val root = noBackup()
            val io = object : VaultMigrationIO() {
                override fun save(candidate: Vault, file: File, password: String): VaultSaveOutcome {
                    val result = super.save(candidate, file, password)
                    return if (reportedCommit) result else VaultSaveOutcome.UNCERTAIN
                }
                override fun load(file: File, password: String): Vault = throw VaultIOException("synthetic read failure")
            }
            val result = migrateVaultToV2(file, root, password, baseline, consent, io)
            assertEquals(if (reportedCommit) VaultSaveOutcome.COMMITTED else VaultSaveOutcome.UNCERTAIN, result.outcome)
            assertTrue(result.requiresReview)
            assertNull(result.vault)
            assertEquals(baseline.copy(version = 2), Vault.load(file, password).data)
            assertArrayEquals(old, migrationArchive(root).readBytes())
        }
    }
}
