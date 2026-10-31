package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun testVaultCreateSaveAndLoad() {
        val vault = Vault.create()
        vault.addEntry(name = "user@example.com", issuer = "GitHub", secret = "JBSWY3DPEHPK3PXP")

        val vaultFile = tempFolder.newFile("vault.d2fa")
        val password = "StrongPassword123!"

        assertEquals(VaultSaveOutcome.COMMITTED, vault.save(vaultFile, password))

        assertTrue(vaultFile.exists())
        assertTrue(vaultFile.length() > Vault.HEADER_LEN + 16)

        val loadedVault = Vault.load(vaultFile, password)
        assertEquals(1, loadedVault.entries.size)

        val entry = loadedVault.getEntry("GitHub")
        assertNotNull(entry)
        assertEquals("GitHub", entry.issuer)
        assertEquals("user@example.com", entry.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", entry.secret)
    }

    @Test
    fun testInvalidPasswordThrowsException() {
        val vault = Vault.create()
        vault.addEntry(name = "user@example.com", issuer = "GitHub", secret = "JBSWY3DPEHPK3PXP")

        val vaultFile = tempFolder.newFile("vault_bad_pass.d2fa")
        vault.save(vaultFile, "CorrectPassword")

        try {
            Vault.load(vaultFile, "WrongPassword")
            fail("Expected InvalidPasswordException was not thrown")
        } catch (e: InvalidPasswordException) {
            // Success
        }
    }

    @Test
    fun testUnsupportedVersionThrowsException() {
        // Build unsupported input without weakening the production writer's validation.
        val vaultFile = encryptedFixture("""{"version":99,"entries":[]}""")

        try {
            Vault.load(vaultFile, "synthetic password")
            fail("Expected UnsupportedFormatException was not thrown")
        } catch (e: UnsupportedFormatException) {
            assertTrue(e.message?.contains("Unsupported vault data version") == true)
        }
    }

    @Test
    fun newVaultCreationRefusesExistingFileAndRecoveryCandidate() {
        val file = tempFolder.newFile("vault.d2fa").apply { writeText("synthetic existing data") }
        val original = file.readBytes()
        assertThrows(VaultIOException::class.java) { Vault.create().saveNew(file, "synthetic password") }
        assertArrayEquals(original, file.readBytes())
        val absent = File(tempFolder.root, "recovery.d2fa")
        val backup = File(tempFolder.root, "recovery.d2fa.bak").apply { writeBytes(original) }
        assertThrows(VaultIOException::class.java) { Vault.create().saveNew(absent, "synthetic password") }
        assertFalse(absent.exists())
        assertArrayEquals(original, backup.readBytes())
    }

    @Test
    fun publishesCompleteNewVaultAndLeavesNoTemporaryLink() {
        val file = File(tempFolder.root, "new.d2fa")
        val vault = Vault.create()
        vault.addEntry("synthetic account", "synthetic service", "MY======")
        vault.saveNew(file, "synthetic password")
        assertEquals(vault.entries, Vault.load(file, "synthetic password").entries)
        assertEquals(listOf(file.name), tempFolder.root.list()!!.toList())
    }

    @Test
    fun unknownRootOrEntryFieldsAreRejectedWithoutChangingFile() {
        for (payload in listOf(
            """{"version":1,"entries":[],"future":{"value":true}}""",
            """{"version":1,"entries":[{"secret":"MY======","future":{"value":true}}]}"""
        )) {
            val file = encryptedFixture(payload)
            val original = file.readBytes()
            assertThrows(CorruptedVaultException::class.java) { Vault.load(file, "synthetic password") }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun supportedNullableAndDuplicateEntriesRetainTheirFields() {
        val file = encryptedFixture("""{"version":1,"entries":[
            {"issuer":null,"account_name":null,"secret":"MY======","digits":7,"period":45,"algorithm":"SHA256"},
            {"issuer":"Synthetic","account_name":"same","secret":"MY======"},
            {"issuer":"Synthetic","account_name":"same","secret":"MY======"}
        ]}""")
        val loaded = Vault.load(file, "synthetic password")
        val entries = loaded.entries
        loaded.save(file, "synthetic password")
        assertEquals(entries, Vault.load(file, "synthetic password").entries)
    }

    @Test
    fun wrongPasswordTamperingAndTruncationNeverChangeStoredBytes() {
        val file = encryptedFixture("""{"version":1,"entries":[]}""")
        val valid = file.readBytes()
        assertThrows(InvalidPasswordException::class.java) { Vault.load(file, "synthetic wrong password") }
        assertArrayEquals(valid, file.readBytes())
        val tampered = valid.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(tampered)
        assertThrows(InvalidPasswordException::class.java) { Vault.load(file, "synthetic password") }
        assertArrayEquals(tampered, file.readBytes())
        val truncated = valid.copyOf(20)
        file.writeBytes(truncated)
        assertThrows(UnsupportedFormatException::class.java) { Vault.load(file, "synthetic password") }
        assertArrayEquals(truncated, file.readBytes())
    }

    private fun encryptedFixture(payload: String): File {
        val salt = CryptoUtils.generateSalt(16)
        val key = CryptoUtils.deriveKey("synthetic password", salt)
        return tempFolder.newFile().apply {
            writeBytes(Vault.VAULT_MAGIC + Vault.VAULT_VERSION + salt + CryptoUtils.encrypt(key, payload.toByteArray(Charsets.UTF_8)))
        }
    }
}
