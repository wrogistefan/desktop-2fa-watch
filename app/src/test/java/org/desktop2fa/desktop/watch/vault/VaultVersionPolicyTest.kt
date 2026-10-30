package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.json.Json
import org.desktop2fa.desktop.watch.model.VaultData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultVersionPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "synthetic version password"
    private val salt by lazy { CryptoUtils.generateSalt(16) }
    private val key by lazy { CryptoUtils.deriveKey(password, salt) }

    @Test
    fun payloadVersionMustBePresentAndAnExactIntegerLiteral() {
        val invalid = listOf(
            """{"entries":[]}""",
            *listOf("null", "true", "false", "\"1\"", "1.0", "1e0", "01", "+1", "[]", "{}", "2147483648")
                .map { """{"version":$it,"entries":[]}""" }.toTypedArray(),
        )
        for (payload in invalid) {
            val file = fixture(payload)
            val original = file.readBytes()
            assertThrows(CorruptedVaultException::class.java) { Vault.load(file, password) }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun headerAndPayloadVersionsAreCheckedIndependently() {
        for (header in listOf(0, 2, 255)) {
            val file = fixture("""{"version":1,"entries":[]}""", header.toByte())
            val original = file.readBytes()
            assertThrows(UnsupportedFormatException::class.java) { Vault.load(file, password) }
            assertArrayEquals(original, file.readBytes())
        }
        for (version in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            val file = fixture("""{"version":$version,"entries":[]}""")
            val original = file.readBytes()
            assertThrows(UnsupportedFormatException::class.java) { Vault.load(file, password) }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun unsupportedWriteVersionCannotReplaceOrCreateAFile() {
        val existing = File(folder.root, "existing.d2fa")
        Vault.create().saveNew(existing, password)
        val original = existing.readBytes()
        for (version in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            val vault = Vault(VaultData(version = version))
            assertThrows(UnsupportedFormatException::class.java) { vault.save(existing, password) }
            assertArrayEquals(original, existing.readBytes())
            val absent = File(folder.root, "not-created.d2fa")
            assertThrows(UnsupportedFormatException::class.java) { vault.saveNew(absent, password) }
            assertFalse(absent.exists())
        }
    }

    @Test
    fun unknownRootAndEntryFieldsWithNestedDataNeverBecomeWritable() {
        for (payload in listOf(
            """{"version":1,"entries":[],"future":{"nested":[{"value":1}]}}""",
            """{"version":1,"entries":[{"secret":"MY======","future":{"nested":[1,2]}}]}""",
        )) {
            val file = fixture(payload)
            val original = file.readBytes()
            assertThrows(CorruptedVaultException::class.java) {
                Vault.load(file, password).addEntry("new", "Synthetic", "MY======")
            }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun supportedPayloadAlwaysEncodesItsVersionAndRoundTrips() {
        assertTrue(Json.encodeToString(VaultData.serializer(), VaultData()).contains("\"version\":1"))
        val file = fixture("""{"entries":[],"version":1}""")
        val loaded = Vault.load(file, password)
        loaded.addEntry("new", "Synthetic", "MY======")
        loaded.save(file, password)
        assertEquals(1, Vault.load(file, password).entries.size)
        assertEquals(1, file.readBytes()[4].toInt())
    }

    private fun fixture(payload: String, headerVersion: Byte = 1): File = folder.newFile().apply {
        writeBytes(Vault.VAULT_MAGIC + byteArrayOf(headerVersion) + salt + CryptoUtils.encrypt(key, payload.toByteArray(Charsets.UTF_8)))
    }
}
