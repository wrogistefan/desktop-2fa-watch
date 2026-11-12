package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.json.Json
import org.desktop2fa.desktop.watch.model.*
import org.desktop2fa.desktop.watch.presentation.AccountAdditionCandidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** All credentials and entries in this suite are synthetic. */
class VaultV2Test {
    @get:Rule val folder = TemporaryFolder()
    private val password = "synthetic v2 password"
    private val receipt = TransferReceipt("1".repeat(32), "2".repeat(64),
        listOf("3".repeat(32)), listOf("4".repeat(32)))
    private val legacy = listOf(
        TotpEntry(issuer = null, accountName = null, secret = " m\ty\n ", digits = 7,
            period = 45, algorithm = "SHA256"),
        TotpEntry(issuer = "Synthetic", accountName = "same", secret = "my"),
        TotpEntry(issuer = "Synthetic", accountName = "same", secret = "MY======"),
    )
    private fun model() = VaultData(2, legacy.toMutableList(), listOf(receipt))

    @Test fun v1StillHasExactlyItsOriginalSchemaAndDoesNotMigrateOnManualSave() {
        val file = File(folder.root, "vault.d2fa")
        Vault.create().saveNew(file, password)
        val vault = Vault.load(file, password)
        vault.addEntry("synthetic account", "Synthetic", "MY")
        assertEquals(VaultSaveOutcome.COMMITTED, vault.save(file, password))
        val bytes = file.readBytes()
        assertEquals(1, bytes[4].toInt())
        val key = CryptoUtils.deriveKey(password, bytes.copyOfRange(5, 21))
        val root = Json.parseToJsonElement(CryptoUtils.decrypt(key,
            bytes.copyOfRange(21, bytes.size)).toString(Charsets.UTF_8))
        assertEquals(setOf("version", "entries"), (root as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test fun restartAndManualAdditionPreserveEveryLegacyFieldAndReceipt() {
        val file = File(folder.root, "vault.d2fa")
        Vault(model()).saveNew(file, password)
        assertEquals(2, file.readBytes()[4].toInt())
        val loaded = Vault.load(file, password)
        assertEquals(model(), loaded.data)
        val candidate = AccountAdditionCandidate(loaded.data,
            listOf(TotpEntry(accountName = "new", secret = "MY======")))
        assertEquals(VaultSaveOutcome.COMMITTED, candidate.vault().save(file, password))
        val restarted = Vault.load(file, password)
        assertEquals(legacy, restarted.entries.take(legacy.size))
        assertEquals(listOf(receipt), restarted.data.transferReceipts)
        assertFalse(candidate.matches(restarted.data))
        assertFalse(candidate.matches(loaded.data.copy(transferReceipts = emptyList())))
    }

    @Test fun candidateOwnsReceiptListsAndDoesNotExposeThemToItsWriter() {
        val ids = mutableListOf("3".repeat(32))
        val receipts = mutableListOf(receipt.copy(addedItemIds = ids))
        val data = model().copy(transferReceipts = receipts)
        val candidate = AccountAdditionCandidate(data, listOf(TotpEntry(secret = "MY")))
        ids.clear(); receipts.clear()
        assertEquals(listOf(receipt), candidate.vault().data.transferReceipts)
        val exposed = candidate.vault()
        assertEquals(listOf(receipt), exposed.data.transferReceipts)
        assertFalse(candidate.matches(data))
    }

    @Test fun invalidReceiptsRejectBeforeAnyFileMutation() {
        val original = folder.newFile("existing").apply { writeText("synthetic original") }
        val bytes = original.readBytes()
        val bad = listOf(
            model().copy(version = 1),
            model().copy(version = 99),
            model().copy(transferReceipts = List(129) { receipt }),
            model().copy(transferReceipts = listOf(receipt, receipt)),
            model().copy(transferReceipts = listOf(receipt.copy(transferId = "A".repeat(32)))),
            model().copy(transferReceipts = listOf(receipt.copy(offerSha256 = "x".repeat(64)))),
            model().copy(transferReceipts = listOf(receipt.copy(addedItemIds = emptyList(), skippedItemIds = emptyList()))),
            model().copy(transferReceipts = listOf(receipt.copy(skippedItemIds = receipt.addedItemIds))),
            model().copy(transferReceipts = listOf(receipt.copy(addedItemIds = List(51) { "%032x".format(it) }))),
            model().copy(transferReceipts = listOf(receipt.copy(addedItemIds = listOf("short")))),
        )
        for (data in bad) {
            val expected = if (data.version == 99) UnsupportedFormatException::class.java
                else IllegalArgumentException::class.java
            assertThrows(expected) { Vault(data).save(original, password) }
            assertArrayEquals(bytes, original.readBytes())
            val absent = File(folder.root, "absent")
            assertThrows(expected) { Vault(data).saveNew(absent, password) }
            assertFalse(absent.exists())
        }
        assertEquals(listOf("existing"), folder.root.list()!!.toList())
    }

    @Test fun roundTripsMaximumReceiptCountInSaveOrder() {
        val receipts = (0 until 128).map { receipt.copy(transferId = "%032x".format(it)) }
        val vault = Vault(model().copy(transferReceipts = receipts))
        val file = File(folder.root, "limit.d2fa")
        vault.saveNew(file, password)
        assertEquals(receipts, Vault.load(file, password).data.transferReceipts)
        assertEquals("TransferReceipt(redacted)", receipt.toString())
    }

    @Test fun wrongPasswordAndTamperedEnvelopeNeverModifyTheFile() {
        val file = File(folder.root, "vault.d2fa")
        Vault(model()).saveNew(file, password)
        val original = file.readBytes()
        assertThrows(InvalidPasswordException::class.java) { Vault.load(file, "synthetic wrong") }
        assertArrayEquals(original, file.readBytes())
        // Magic, version downgrade, salt, nonce, ciphertext and tag.
        for (offset in listOf(0, 4, 5, 21, 33, original.lastIndex)) {
            val altered = original.copyOf()
            altered[offset] = (altered[offset].toInt() xor if (offset == 4) 3 else 1).toByte()
            file.writeBytes(altered)
            assertThrows(VaultException::class.java) { Vault.load(file, password) }
            assertArrayEquals(altered, file.readBytes())
        }
    }

    @Test fun v2CannotBeDecryptedWithoutExactHeaderAad() {
        val key = ByteArray(32) { 7 }
        val data = "synthetic marker".toByteArray()
        val header = Vault.VAULT_MAGIC + byteArrayOf(2)
        val encrypted = CryptoUtils.encrypt(key, data, header)
        assertArrayEquals(data, CryptoUtils.decrypt(key, encrypted, header))
        assertThrows(Exception::class.java) { CryptoUtils.decrypt(key, encrypted) }
        assertThrows(Exception::class.java) { CryptoUtils.decrypt(key, encrypted, Vault.VAULT_MAGIC + byteArrayOf(1)) }
    }

    @Test fun authenticatedMalformedV2IsRejectedWithoutChangingItsBytes() {
        val valid = """{"version":2,"entries":[],"transfer_receipts":[]}"""
        val invalid = listOf(
            valid.replace("\"version\":2", "\"version\":1"),
            valid.replace("\"version\":2", "\"version\":2,\"version\":2"),
            valid.replace("\"version\":2", "\"version\":2.0"),
            """{"version":2,"entries":[]}""",
            valid.replace("\"entries\":[]", "\"entries\":[],\"extra\":null"),
            """{"version":2,"entries":[],"transfer_receipts":[{"transfer_id":"invalid"}]}""",
        )
        for (payload in invalid) {
            val file = fixture(payload.toByteArray())
            val original = file.readBytes()
            assertThrows(VaultException::class.java) { Vault.load(file, password) }
            assertArrayEquals(original, file.readBytes())
        }
        val malformedUtf8 = fixture(valid.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(CorruptedVaultException::class.java) { Vault.load(malformedUtf8, password) }
    }

    @Test fun payloadDiagnosticsIdentifyTheStageWithoutChainingDecryptedInput() {
        val marker = "SYNTHETIC_ONLY_DO_NOT_LOG"
        val valid = """{"version":2,"entries":[{"secret":"MY======","account_name":"$marker"}],"transfer_receipts":[]}"""
        val cases = listOf(
            (valid.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)) to VaultPayloadPhase.UTF8,
            valid.replace("\"version\":2", "\"version\":2,\"version\":2").toByteArray() to VaultPayloadPhase.JSON_SYNTAX,
            valid.replace("\"secret\":", "\"digits\":\"$marker\",\"secret\":").toByteArray() to VaultPayloadPhase.JSON_TYPES,
            valid.replace("\"version\":2", "\"unexpected\":\"$marker\",\"version\":2").toByteArray() to VaultPayloadPhase.MODEL_SCHEMA,
        )
        for ((payload, expectedPhase) in cases) {
            val error = assertThrows(CorruptedVaultException::class.java) { Vault.load(fixture(payload), password) }
            val diagnostic = error.cause as VaultPayloadDiagnosticException
            assertEquals(expectedPhase, diagnostic.phase)
            assertNull(diagnostic.cause)
            val trace = java.io.StringWriter()
            error.printStackTrace(java.io.PrintWriter(trace))
            assertFalse(trace.toString().contains(marker))
            assertFalse(trace.toString().contains("MY======"))
        }
    }

    @Test fun v1RefusesReceiptFieldsAndHeaderPayloadMismatch() {
        for (payload in listOf(
            """{"version":1,"entries":[],"transfer_receipts":[]}""",
            """{"version":2,"entries":[]}""",
        )) {
            val file = fixture(payload.toByteArray(), 1)
            val original = file.readBytes()
            assertThrows(VaultException::class.java) { Vault.load(file, password) }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun precommitAndUncertainPublicationKeepAccountsAndReceiptsTogether() {
        for (afterMove in listOf(false, true)) {
            val directory = folder.newFolder()
            val file = File(directory, "vault.d2fa")
            val old = model().copy(transferReceipts = emptyList())
            Vault(old).saveNew(file, password)
            val candidate = model().copy(entries = (legacy + TotpEntry(secret = "MY")).toMutableList())
            val io = object : VaultReplacementIO() {
                override fun replace(temp: File, destination: File) {
                    if (afterMove) super.replace(temp, destination)
                    throw IOException("synthetic publication error")
                }
            }
            assertEquals(VaultSaveOutcome.UNCERTAIN, Vault(candidate).save(file, password, io))
            assertEquals(if (afterMove) candidate else old, Vault.load(file, password).data)
            assertEquals(old, Vault.load(File(directory, "vault.d2fa.bak"), password).data)
        }
    }

    private fun fixture(payload: ByteArray, version: Int = 2): File {
        val header = Vault.VAULT_MAGIC + byteArrayOf(version.toByte())
        val salt = CryptoUtils.generateSalt(16)
        val key = CryptoUtils.deriveKey(password, salt)
        return folder.newFile().apply {
            writeBytes(header + salt + CryptoUtils.encrypt(key, payload, header.takeIf { version == 2 }))
        }
    }
}
