package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.model.*
import org.desktop2fa.desktop.watch.transfer.*
import org.desktop2fa.desktop.watch.vault.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferStatusStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val password = "Synthetic-Status-Only-42!"
    private val receipt = TransferReceipt("1".repeat(32), "2".repeat(64), listOf("3".repeat(32)), emptyList())
    private fun fixture(): Pair<File, VaultSessionStorage> {
        val file = File(temporary.root, "synthetic.d2fa")
        Vault(VaultData(2, mutableListOf(TotpEntry("synthetic", "synthetic account", "AA")), listOf(receipt))).saveNew(file, password)
        return file to VaultSessionStorage(file, temporary.newFolder("no-backup")) { true }
    }
    @Test fun statusAuthenticatesActualEncryptedFileWithoutRewritingOrReordering() {
        val (file, storage) = fixture(); val bytes = file.readBytes()
        assertEquals(receipt, storage.transferStatus(receipt.transferId, receipt.offerSha256, password))
        assertNull(storage.transferStatus("4".repeat(32), "5".repeat(64), password))
        try { storage.transferStatus(receipt.transferId, "6".repeat(64), password); fail() }
        catch (failure: TransferValidationFailure) { assertEquals(TransferError.TX_CONFLICT, failure.code) }
        try { storage.transferStatus(receipt.transferId, receipt.offerSha256, "Synthetic-Wrong-42!"); fail() }
        catch (_: InvalidPasswordException) {}
        assertArrayEquals(bytes, file.readBytes()); assertEquals(listOf(receipt), Vault.load(file, password).data.transferReceipts)
    }
    @Test fun malformedIdentifiersAreRejectedBeforeAnyFileOrPasswordAccess() {
        val missing = File(temporary.root, "synthetic-nonexistent.d2fa")
        val storage = VaultSessionStorage(missing) { true }
        for ((id, sha) in listOf("invalid" to receipt.offerSha256, receipt.transferId to "invalid")) {
            try { storage.transferStatus(id, sha, ""); fail() }
            catch (failure: TransferValidationFailure) { assertEquals(TransferError.INVALID_MESSAGE, failure.code) }
        }
        assertFalse(missing.exists())
    }
    @Test fun recoveryArtifactsOrCorruptionNeverBecomeUnknownOrEmptyStorage() {
        val (file, storage) = fixture(); val bytes = file.readBytes()
        val pending = File(file.path + ".tmp"); pending.writeBytes(byteArrayOf(1))
        try { storage.transferStatus(receipt.transferId, receipt.offerSha256, password); fail() }
        catch (failure: TransferValidationFailure) { assertEquals(TransferError.STORAGE_ERROR, failure.code) }
        assertArrayEquals(bytes, file.readBytes()); assertTrue(pending.delete())
        val damaged = bytes.copyOf(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte(); file.writeBytes(damaged)
        try { storage.transferStatus(receipt.transferId, receipt.offerSha256, password); fail() }
        catch (_: VaultException) {}
        assertArrayEquals(damaged, file.readBytes())
    }
}
