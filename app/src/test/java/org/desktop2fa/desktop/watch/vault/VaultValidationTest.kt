package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.model.VaultData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class VaultValidationTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "synthetic validation password"

    @Test
    fun invalidAdditionDoesNotMutateExistingEntries() {
        val vault = Vault.create()
        vault.addEntry("existing", "Synthetic", "MY======")
        val before = vault.entries
        for (secret in listOf("", "MZ", "M0", "\u017fy")) {
            assertThrows(IllegalArgumentException::class.java) { vault.addEntry("new", "Synthetic", secret) }
            assertEquals(before, vault.entries)
        }
        vault.addEntry("new", "Synthetic", " m\ty\n ")
        assertEquals("MY======", vault.entries.last().secret)
    }

    @Test
    fun invalidLoadedParametersRejectTheWholeVaultWithoutChangingItsBytes() {
        for (entry in invalidEntries()) {
            val file = fixture(listOf(entry))
            val before = file.readBytes()
            val error = assertThrows(CorruptedVaultException::class.java) { Vault.load(file, password) }
            assertEquals("Vault contains invalid account data", error.message)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test
    fun invalidSaveSnapshotsDoNotReplaceExistingDataOrCreateANewFile() {
        val file = File(folder.root, "existing.d2fa")
        Vault.create().saveNew(file, password)
        val before = file.readBytes()
        for (entry in invalidEntries()) {
            val vault = Vault(VaultData(entries = mutableListOf(entry)))
            val entries = vault.entries
            assertThrows(IllegalArgumentException::class.java) { vault.save(file, password) }
            assertArrayEquals(before, file.readBytes())
            assertEquals(entries, vault.entries)
            val newFile = File(folder.root, "not-created.d2fa")
            assertThrows(IllegalArgumentException::class.java) { vault.saveNew(newFile, password) }
            assertFalse(newFile.exists())
        }
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test
    fun validHistoricalKeysNullableNamesAndDuplicatesSurviveAnAdditionAndSave() {
        val entries = listOf(
            TotpEntry(issuer = "Syntetyczny \u017begarek", secret = " m\ty\n ", digits = 7, period = 45, algorithm = "SHA256"),
            TotpEntry(issuer = "Synthetic", accountName = "same", secret = "my", digits = 8, period = 60, algorithm = "SHA512"),
            TotpEntry(issuer = "Synthetic", accountName = "same", secret = "MY======"),
        )
        val file = fixture(entries)
        val loaded = Vault.load(file, password)
        assertEquals(entries, loaded.entries)
        loaded.addEntry("new", "Synthetic", "MY")
        loaded.save(file, password)
        val reloaded = Vault.load(file, password)
        assertEquals(entries, reloaded.entries.take(entries.size))
        assertEquals("MY======", reloaded.entries.last().secret)
    }

    @Test
    fun pinnedPythonFixtureRemainsReadableAndPreservesEverySupportedField() {
        val metadata = javaClass.getResourceAsStream("/desktop-v0.8.3.json")!!.use {
            Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
        }
        val bytes = javaClass.getResourceAsStream("/desktop-v0.8.3.bin")!!.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(metadata.getValue("sha256").jsonPrimitive.content, digest)
        val fixturePassword = metadata.getValue("password").jsonPrimitive.content
        val expected = Json.decodeFromJsonElement(VaultData.serializer(), metadata.getValue("expected"))
        val file = folder.newFile().apply { writeBytes(bytes) }
        val loaded = Vault.load(file, fixturePassword)
        assertEquals(expected.entries, loaded.entries)
        loaded.save(file, fixturePassword)
        assertEquals(expected.entries, Vault.load(file, fixturePassword).entries)
    }

    private fun invalidEntries(): List<TotpEntry> = listOf(
        TotpEntry(secret = ""),
        TotpEntry(secret = "MY======", algorithm = "MD5"),
        TotpEntry(secret = "MY======", digits = 10),
        TotpEntry(secret = "MY======", period = 0),
    )

    // Bypass the production writer to exercise authenticated but invalid historical input.
    private fun fixture(entries: List<TotpEntry>): File {
        val salt = CryptoUtils.generateSalt(16)
        val key = CryptoUtils.deriveKey(password, salt)
        val payload = Json.encodeToString(VaultData.serializer(), VaultData(entries = entries.toMutableList()))
        return folder.newFile().apply {
            writeBytes(Vault.VAULT_MAGIC + Vault.VAULT_VERSION + salt + CryptoUtils.encrypt(key, payload.toByteArray(Charsets.UTF_8)))
        }
    }
}
