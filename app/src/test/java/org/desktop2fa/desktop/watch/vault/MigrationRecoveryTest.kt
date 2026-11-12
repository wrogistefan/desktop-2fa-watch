package org.desktop2fa.desktop.watch.vault

import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.presentation.Screen
import org.desktop2fa.desktop.watch.presentation.VaultSessionStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class MigrationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val password = "synthetic-żółw-compatibility-only"
    private fun fixture() = javaClass.getResourceAsStream("/desktop-v0.8.3.bin")!!.use { it.readBytes() }
    private fun archive(root: File, bytes: ByteArray = fixture()): File = migrationArchive(root).also {
        it.parentFile!!.mkdir(); it.writeBytes(bytes)
    }
    // Unexpected runtime exceptions must fail the test, not masquerade as controlled rejection.
    private inline fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: VaultException) {}
    }

    @Test fun absentMainWithAnyMigrationDirectoryDisablesEmptyInitialization() {
        for (hasMain in listOf(false, true)) for (copy in 0..2) {
            val files = temporary.newFolder(); val root = temporary.newFolder()
            val main = File(files, "vault.d2fa")
            if (hasMain) main.writeText("synthetic main placeholder")
            if (copy >= 1) migrationArchive(root).parentFile!!.mkdir()
            if (copy == 2) migrationArchive(root).writeText("synthetic partial copy")
            val inventory = migrationAwareInventory(main, root)
            assertEquals(if (hasMain) VaultPresence.PRESENT else if (copy == 0) VaultPresence.MISSING
                else VaultPresence.RECOVERY_REQUIRED, inventory.presence)
            assertEquals((if (hasMain) 1 else 0) + (if (copy == 2) 1 else 0), inventory.candidates.size)
            assertEquals(hasMain, main.exists())
        }
    }
    @Test fun existingOrdinaryRecoveryArtifactsStillWinOverHealthyMainAndArchive() {
        val files = temporary.newFolder(); val root = temporary.newFolder()
        val main = File(files, "vault.d2fa").also { it.writeText("synthetic main") }
        File(files, "vault.d2fa.tmp").writeText("synthetic interrupted write")
        archive(root)
        val inventory = migrationAwareInventory(main, root)
        assertEquals(VaultPresence.RECOVERY_REQUIRED, inventory.presence)
        assertEquals(3, inventory.candidates.size)
    }
    @Test fun wrongPasswordAndAuthenticatedReadonlyOpenPreserveAllFiles() {
        val files = temporary.newFolder(); val root = temporary.newFolder(); val main = File(files, "vault.d2fa")
        val copy = archive(root); val original = copy.readBytes()
        val candidate = migrationAwareInventory(main, root).candidates.single()
        rejected { openMigrationAwareRecoveryView(main, root, candidate, "synthetic-wrong-password") }
        val view = openMigrationAwareRecoveryView(main, root, candidate, password)
        assertEquals(1, view.vault.data.version); assertFalse(view.vault.entries.isEmpty())
        assertTrue(view.sourceLabel.contains("read-only")); assertTrue(view.sourceLabel.contains("newer accounts"))
        assertArrayEquals(original, copy.readBytes()); assertFalse(main.exists())
        assertEquals(listOf("vault-v1.d2fa"), copy.parentFile!!.list()!!.toList())
    }
    @Test fun changedOrUnlistedCandidateNeverReachesDecryption() {
        val files = temporary.newFolder(); val root = temporary.newFolder(); val main = File(files, "vault.d2fa")
        val copy = archive(root); val candidate = migrationAwareInventory(main, root).candidates.single()
        Files.setLastModifiedTime(copy.toPath(), FileTime.fromMillis(candidate.fingerprint!!.modified.toMillis() + 2000))
        var opened = false
        rejected { openMigrationAwareRecoveryView(main, root, candidate, password) { _, _ -> opened = true; Vault() } }
        assertFalse(opened)
        val outside = File(temporary.root, "unlisted.d2fa").also { it.writeBytes(fixture()) }
        rejected { openMigrationAwareRecoveryView(main, root, RecoveryCandidate(outside, "synthetic"), password) { _, _ -> opened = true; Vault() } }
        assertFalse(opened); assertFalse(main.exists())
    }
    @Test fun changeDuringOpenIsRejectedAndV2CannotMasqueradeAsPreMigrationCopy() {
        val files = temporary.newFolder(); val root = temporary.newFolder(); val main = File(files, "vault.d2fa")
        val copy = archive(root); val original = copy.readBytes()
        val candidate = migrationAwareInventory(main, root).candidates.single()
        rejected { openMigrationAwareRecoveryView(main, root, candidate, password) { _, _ ->
            Files.setLastModifiedTime(copy.toPath(), FileTime.fromMillis(candidate.fingerprint!!.modified.toMillis() + 2000))
            Vault(VaultData(1))
        } }
        val current = migrationAwareInventory(main, root).candidates.single()
        rejected { openMigrationAwareRecoveryView(main, root, current, password) { _, _ -> Vault(VaultData(2)) } }
        assertArrayEquals(original, copy.readBytes()); assertFalse(main.exists())
    }
    @Test fun storageRechecksLateArchiveBeforeCreatingAnyVault() {
        val files = temporary.newFolder(); val root = temporary.newFolder(); val main = File(files, "vault.d2fa")
        val storage = VaultSessionStorage(main, root) { true }
        assertEquals(Screen.SETUP, storage.inspect().screen)
        val copy = archive(root); val before = copy.readBytes()
        assertEquals(Screen.STORAGE_ERROR, storage.inspect().screen)
        rejected { storage.create("Synthetic-Strong-Password-42!") }
        assertFalse(main.exists()); assertArrayEquals(before, copy.readBytes())
        assertEquals(1, storage.inventory().size)
        assertEquals(1, storage.recover(storage.inventory().single(), password).vault.data.version)
    }
    @Test fun nonDirectoryAndNonRegularArchivePathsNeverAuthorizeSetup() {
        val main = File(temporary.newFolder(), "vault.d2fa")
        val wrongRoot = temporary.newFile()
        rejected { migrationAwareInventory(main, wrongRoot) }
        val root = temporary.newFolder(); val path = migrationArchive(root)
        path.parentFile!!.writeText("synthetic not a directory")
        rejected { migrationAwareInventory(main, root) }
        val otherRoot = temporary.newFolder(); val otherPath = migrationArchive(otherRoot)
        otherPath.parentFile!!.mkdir(); otherPath.mkdir()
        rejected { migrationAwareInventory(main, otherRoot) }
        assertFalse(main.exists())
    }
    @Test fun corruptArchiveDoesNotCreateMainOrReplaceSafetyCopy() {
        val main = File(temporary.newFolder(), "vault.d2fa"); val root = temporary.newFolder()
        val copy = archive(root, byteArrayOf(1, 2, 3)); val bytes = copy.readBytes()
        rejected { openMigrationAwareRecoveryView(main, root, migrationAwareInventory(main, root).candidates.single(), password) }
        assertArrayEquals(bytes, copy.readBytes()); assertFalse(main.exists())
    }
    @Test fun positivelyAbsentNoBackupDirectoryDoesNotBreakOrdinaryOfflineStartup() {
        val main = File(temporary.newFolder(), "vault.d2fa")
        val root = File(temporary.newFolder(), "not-created-yet")
        assertEquals(VaultPresence.MISSING, migrationAwareInventory(main, root).presence)
        main.writeText("synthetic existing main")
        assertEquals(VaultPresence.PRESENT, migrationAwareInventory(main, root).presence)
        assertFalse(root.exists())
    }
    @Test fun symlinkedRecoveryPathsAreRejectedWithoutFollowingThem() {
        val main = File(temporary.newFolder(), "vault.d2fa")
        val root = temporary.newFolder()
        val copy = migrationArchive(root); copy.parentFile!!.mkdir()
        val target = temporary.newFile().also { it.writeBytes(fixture()) }
        try { Files.createSymbolicLink(copy.toPath(), target.toPath()) }
        catch (unsupported: Exception) {
            // Linux CI must execute this case. Windows may lack symlink privilege.
            if (!System.getProperty("os.name").startsWith("Windows") ||
                (unsupported !is java.nio.file.FileSystemException && unsupported !is UnsupportedOperationException)) throw unsupported
            org.junit.Assume.assumeNoException("Symlink creation unavailable on Windows", unsupported)
        }
        rejected { migrationAwareInventory(main, root) }
        val linkedRoot = File(temporary.newFolder(), "linked-root")
        Files.createSymbolicLink(linkedRoot.toPath(), root.toPath())
        rejected { migrationAwareInventory(main, linkedRoot) }
        assertFalse(main.exists()); assertArrayEquals(fixture(), target.readBytes())
    }

    @Test fun metadataFailureIsNeverInterpretedAsAbsenceOrSetupAuthorization() {
        val main = File(temporary.newFolder(), "vault.d2fa"); val root = temporary.newFolder()
        val startup = org.desktop2fa.desktop.watch.presentation.resolveVaultStartup(main) {
            migrationAwareInventory(main, root) { throw java.nio.file.AccessDeniedException("synthetic-denied") }.presence
        }
        assertEquals(Screen.STORAGE_ERROR, startup.screen)
        assertFalse(main.exists()); assertTrue(root.list()!!.isEmpty())
    }

}
