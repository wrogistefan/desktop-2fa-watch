package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSaveOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultSessionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

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
        val committed = first.add(firstSnapshot, AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic first", key), password)
        assertEquals(VaultSaveOutcome.COMMITTED, committed.outcome)
        val bytes = file.readBytes()
        val rejected = second.add(staleSnapshot, AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic second", key), password)
        assertEquals(VaultSaveOutcome.NOT_COMMITTED, rejected.outcome)
        assertTrue(rejected.requiresReview)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf("synthetic old", "synthetic first"), Vault.load(file, password).entries.map { it.accountName })
        assertEquals(1, original.entries.size)
        assertEquals(1, staleSnapshot.entries.size)
    }
}
