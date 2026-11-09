package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.vault.VaultSaveOutcome
import org.desktop2fa.desktop.watch.vault.VaultReplacementIO
import org.desktop2fa.desktop.watch.vault.VaultOutput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class VaultSessionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun encryptedBatchIsPublishedOnceOrPreservesAllOriginalBytesOnFailure() {
        for (fail in listOf(false, true)) {
            val file = File(temporary.newFolder(), "synthetic.d2fa")
            val password = "Synthetic-Batch-42!"
            val key = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
            val baseline = Vault.create().apply { addEntry("synthetic old", "synthetic", key) }
            baseline.saveNew(file, password)
            val bytes = file.readBytes()
            val entries = listOf(TotpEntry(null, "synthetic one", key), TotpEntry("synthetic", "synthetic two", key, 8, 60, "SHA512"))
            val candidate = AccountAdditionCandidate(baseline.data, entries)
            var writes = 0
            val result = persistAccountCandidate(candidate.vault(), file, password, save = { vault, target, supplied ->
                writes++
                if (fail) vault.save(target, supplied, object : VaultReplacementIO() {
                    override fun openTemp(file: File): VaultOutput = throw IOException("synthetic failure")
                }) else vault.save(target, supplied)
            })
            assertEquals(1, writes)
            assertEquals(1, baseline.entries.size)
            if (fail) {
                assertEquals(VaultSaveOutcome.NOT_COMMITTED, result.outcome)
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(baseline.entries, Vault.load(file, password).entries)
            } else {
                assertEquals(VaultSaveOutcome.COMMITTED, result.outcome)
                assertEquals(baseline.entries + entries, Vault.load(file, password).entries)
                assertEquals(baseline.entries + entries, result.vault!!.entries)
            }
        }
    }

    @Test fun aSecondLiveOwnersStaleSnapshotCannotOverwriteTheFirstCommittedAddition() {
        val file = File(temporary.root, "synthetic.d2fa")
        val password = "Synthetic-Stale-Snapshot-42!"
        val key = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val original = Vault.create().apply { addEntry("synthetic old", "synthetic", key) }
        original.saveNew(file, password)
        val firstSnapshot = original.data.copy(entries = original.entries.toMutableList())
        val staleSnapshot = original.data.copy(entries = original.entries.toMutableList())
        val first = VaultSessionStorage(file) { true }
        val second = VaultSessionStorage(file) { true }
        val committed = first.commit(AccountAdditionCandidate(firstSnapshot, listOf(TotpEntry("synthetic", "synthetic first", key))), password)
        assertEquals(VaultSaveOutcome.COMMITTED, committed.outcome)
        val bytes = file.readBytes()
        val rejected = second.commit(AccountAdditionCandidate(staleSnapshot, listOf(TotpEntry("synthetic", "synthetic second", key))), password)
        assertEquals(VaultSaveOutcome.NOT_COMMITTED, rejected.outcome)
        assertTrue(rejected.requiresReview)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf("synthetic old", "synthetic first"), Vault.load(file, password).entries.map { it.accountName })
        assertEquals(1, original.entries.size)
        assertEquals(1, staleSnapshot.entries.size)
    }
}
