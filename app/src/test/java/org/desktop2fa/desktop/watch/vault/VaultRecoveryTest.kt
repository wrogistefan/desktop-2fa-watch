package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class VaultRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "synthetic-żółw-compatibility-only"
    private fun fixture() = javaClass.getResourceAsStream("/desktop-v0.8.3.bin")!!.use { it.readBytes() }

    @Test
    fun restartTableIncludesMainBackupFixedTempAndLegacyTempWithoutChangingAnyBytes() {
        val cases = listOf(
            emptyList(), listOf("vault.d2fa"), listOf("vault.d2fa.bak"), listOf("vault.d2fa.tmp"),
            listOf("vault.d2fa", "vault.d2fa.bak"), listOf("vault.d2fa", "vault.d2fa.tmp", "vault.d2fa.bak"),
            listOf(".vault.123.tmp"), listOf("vault.d2fa", ".vault.123.tmp"),
        )
        cases.forEachIndexed { index, names ->
            val directory = folder.newFolder("case$index")
            names.forEach { File(directory, it).writeText("synthetic possibly partial candidate: $it") }
            val main = File(directory, "vault.d2fa")
            val expected = when {
                names.isEmpty() -> VaultPresence.MISSING
                names == listOf("vault.d2fa") -> VaultPresence.PRESENT
                else -> VaultPresence.RECOVERY_REQUIRED
            }
            assertEquals(expected, VaultFiles.inspect(main))
            assertEquals(names.toSet(), VaultFiles.inventory(main).candidates.map { it.file.name }.toSet())
            assertEquals(names.toSet(), directory.list()!!.toSet())
            names.forEach { assertEquals("synthetic possibly partial candidate: $it", File(directory, it).readText()) }
        }
    }

    @Test
    fun authenticatedBackupCanBeViewedWithoutPromotingItOrTouchingMainAndPendingData() {
        val main = folder.newFile("vault.d2fa").apply { writeText("synthetic interrupted legacy restore") }
        val backup = folder.newFile("vault.d2fa.bak").apply { writeBytes(fixture()) }
        val pending = folder.newFile("vault.d2fa.tmp").apply { writeText("synthetic partial pending write") }
        val before = folder.root.listFiles()!!.associate { it.name to it.readBytes() }
        val selected = VaultFiles.inventory(main).candidates.single { it.file == backup }
        val view = openRecoveryView(main, selected.copy(label = "untrusted label"), password)
        assertEquals("Previous backup", view.sourceLabel)
        assertTrue(view.vault.entries.isNotEmpty())
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(folder.root, name).readBytes()) }
        assertTrue(pending.exists())
        assertThrows(VaultIOException::class.java) { view.vault.saveNew(main, password) }
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(folder.root, name).readBytes()) }
    }

    @Test
    fun passwordTamperTruncationAndUnsupportedErrorsPreserveEveryCandidate() {
        val main = folder.newFile("vault.d2fa").apply { writeBytes(fixture()) }
        val backup = folder.newFile("vault.d2fa.bak")
        val originalMain = main.readBytes()
        val valid = fixture()
        val cases = listOf(
            valid to "synthetic wrong password",
            valid.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } to password,
            valid.copyOf(20) to password,
            valid.clone().apply { this[4] = 99 } to password,
        )
        for ((bytes, suppliedPassword) in cases) {
            backup.writeBytes(bytes)
            val selected = VaultFiles.inventory(main).candidates.single { it.file == backup }
            assertThrows(VaultException::class.java) { openRecoveryView(main, selected, suppliedPassword) }
            assertArrayEquals(originalMain, main.readBytes())
            assertArrayEquals(bytes, backup.readBytes())
            assertEquals(VaultPresence.RECOVERY_REQUIRED, VaultFiles.inspect(main))
        }
    }

    @Test
    fun candidateRemovalAndReadFailureCannotReturnAViewOrInitializeAnotherVault() {
        val main = File(folder.root, "vault.d2fa")
        val backup = folder.newFile("vault.d2fa.bak").apply { writeBytes(fixture()) }
        val selected = VaultFiles.inventory(main).candidates.single()
        assertThrows(IOException::class.java) { openRecoveryView(main, selected, password) { _, _ -> throw IOException("synthetic") } }
        assertArrayEquals(fixture(), backup.readBytes())
        assertFalse(main.exists())
        assertTrue(backup.delete())
        assertThrows(VaultIOException::class.java) { openRecoveryView(main, selected, password) }
        assertFalse(main.exists())
        assertThrows(VaultIOException::class.java) {
            openRecoveryView(main, RecoveryCandidate(File(folder.root, "unlisted.d2fa"), "fake"), password)
        }
    }

    @Test
    fun pendingOnlyAndLegacyOnlyFilesBlockNewSetup() {
        for (name in listOf("vault.d2fa.tmp", ".vault.synthetic.tmp")) {
            val directory = folder.newFolder()
            val main = File(directory, "vault.d2fa")
            val pending = File(directory, name).apply { writeBytes(fixture()) }
            assertThrows(VaultIOException::class.java) { Vault.create().saveNew(main, password) }
            assertFalse(main.exists())
            assertArrayEquals(fixture(), pending.readBytes())
            assertTrue(openRecoveryView(main, VaultFiles.inventory(main).candidates.single(), password).vault.entries.isNotEmpty())
            assertFalse(main.exists())
        }
    }

    @Test
    fun excessiveOrInvalidRecoveryInventoryFailsClosed() {
        val main = File(folder.root, "vault.d2fa")
        repeat(254) { File(folder.root, ".vault.$it.tmp").writeText("synthetic") }
        assertThrows(VaultIOException::class.java) { VaultFiles.inspect(main) }
        assertFalse(main.exists())
        val other = folder.newFolder()
        assertTrue(File(other, "vault.d2fa.tmp").mkdir())
        assertThrows(VaultIOException::class.java) { VaultFiles.inspect(File(other, "vault.d2fa")) }
    }
}
