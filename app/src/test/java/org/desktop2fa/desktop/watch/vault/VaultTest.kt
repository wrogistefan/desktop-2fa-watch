package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

        vault.save(vaultFile, password)

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
}
