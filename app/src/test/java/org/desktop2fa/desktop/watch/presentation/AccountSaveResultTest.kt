package org.desktop2fa.desktop.watch.presentation

import kotlinx.coroutines.CancellationException
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.vault.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException

class AccountSaveResultTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "synthetic save password"

    private fun prepared(): Pair<File, Vault> {
        val file = File(folder.newFolder(), "vault.d2fa")
        val original = Vault.create().apply { addEntry("synthetic old", "synthetic service", "MY======") }
        original.saveNew(file, password)
        val updated = Vault(VaultData(entries = original.entries.toMutableList())).apply {
            addEntry("synthetic new", "synthetic service", "MZXQ====")
        }
        assertEquals(1, original.entries.size)
        return file to updated
    }

    @Test
    fun prepublicationFailureIsNotCommittedAndNeverReloadsOrRepeatsTheWrite() {
        val (file, updated) = prepared()
        val original = file.readBytes()
        var saveCalls = 0
        var loadCalls = 0
        val result = persistAccountCandidate(updated, file, password, save = { vault, destination, supplied ->
            saveCalls++
            vault.save(destination, supplied, object : VaultReplacementIO() {
                override fun openTemp(file: File): VaultOutput = throw IOException("synthetic private diagnostic")
            })
        }, load = { _, _ -> loadCalls++; error("No verification before commit") })
        assertEquals(VaultSaveOutcome.NOT_COMMITTED, result.outcome)
        assertTrue(result.requiresReview)
        assertEquals(1, saveCalls)
        assertEquals(0, loadCalls)
        assertArrayEquals(original, file.readBytes())
        assertFalse(result.message.contains("synthetic private diagnostic"))
    }

    @Test
    fun unsupportedAtomicMoveIsNotCommittedAndPreservesOldBytes() {
        val (file, updated) = prepared()
        val original = file.readBytes()
        val result = persistAccountCandidate(updated, file, password, save = { vault, destination, supplied ->
            vault.save(destination, supplied, object : VaultReplacementIO() {
                override fun replace(temp: File, destination: File) {
                    throw AtomicMoveNotSupportedException(temp.path, destination.path, "synthetic unsupported")
                }
            })
        })
        assertEquals(VaultSaveOutcome.NOT_COMMITTED, result.outcome)
        assertArrayEquals(original, file.readBytes())
        assertTrue(result.requiresReview)
    }

    @Test
    fun exceptionsAroundPublicationRemainUncertainWithoutRollbackOrAutomaticRetry() {
        for (afterMove in listOf(false, true)) {
            val (file, updated) = prepared()
            val original = file.readBytes()
            var saveCalls = 0
            var loadCalls = 0
            val result = persistAccountCandidate(updated, file, password, save = { vault, destination, supplied ->
                saveCalls++
                vault.save(destination, supplied, object : VaultReplacementIO() {
                    override fun replace(temp: File, destination: File) {
                        if (afterMove) super.replace(temp, destination)
                        throw IOException("synthetic move uncertainty")
                    }
                })
            }, load = { _, _ -> loadCalls++; error("Uncertain writes require explicit review") })
            assertEquals(VaultSaveOutcome.UNCERTAIN, result.outcome)
            assertTrue(result.requiresReview)
            assertEquals(1, saveCalls)
            assertEquals(0, loadCalls)
            assertArrayEquals(original, File(file.parentFile, "${file.name}.bak").readBytes())
            if (afterMove) assertEquals(updated.entries, Vault.load(file, password).entries)
            else assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun reloadFailureCannotTurnACommittedAdditionIntoARetryableDraft() {
        val (file, updated) = prepared()
        var saveCalls = 0
        val result = persistAccountCandidate(updated, file, password, save = { vault, destination, supplied ->
            saveCalls++; vault.save(destination, supplied)
        }, load = { _, _ -> throw IOException("synthetic post-save read failure") })
        assertEquals(VaultSaveOutcome.COMMITTED, result.outcome)
        assertTrue(result.verificationFailed)
        assertTrue(result.requiresReview)
        assertNull(result.vault)
        assertTrue(result.message.startsWith("Account was saved"))
        assertEquals(1, saveCalls)
        val reconciled = Vault.load(file, password)
        assertEquals(updated.entries, reconciled.entries)
        val retry = AccountWizardState(step = AccountStep.SECRET, issuer = "synthetic service",
            account = "synthetic new", secret = "MZXQ====").submit(reconciled.entries.map { it.accountName })
        assertFalse(retry.isSaving)
        assertEquals(2, reconciled.entries.size)
    }

    @Test
    fun cleanupFailureKeepsCommitAndRequiresReviewRatherThanAnotherAddition() {
        val (file, updated) = prepared()
        val original = file.readBytes()
        val result = persistAccountCandidate(updated, file, password, save = { vault, destination, supplied ->
            vault.save(destination, supplied, object : VaultReplacementIO() {
                override fun deleteBackup(backup: File) { throw IOException("synthetic cleanup failure") }
            })
        })
        assertEquals(VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS, result.outcome)
        assertTrue(result.requiresReview)
        assertFalse(result.verificationFailed)
        assertEquals(updated.entries, result.vault!!.entries)
        assertArrayEquals(original, File(file.parentFile, "${file.name}.bak").readBytes())
        val committed = file.readBytes()
        assertEquals(VaultSaveOutcome.NOT_COMMITTED, updated.save(file, password))
        assertArrayEquals(committed, file.readBytes())
    }

    @Test
    fun successfulVerificationReturnsOnlyTheMatchingPersistedSnapshot() {
        val (file, updated) = prepared()
        val result = persistAccountCandidate(updated, file, password)
        assertEquals(VaultSaveOutcome.COMMITTED, result.outcome)
        assertFalse(result.requiresReview)
        assertEquals(updated.entries, result.vault!!.entries)
        val other = prepared()
        val mismatched = persistAccountCandidate(other.second, other.first, password, load = { _, _ -> Vault.create() })
        assertEquals(VaultSaveOutcome.COMMITTED, mismatched.outcome)
        assertTrue(mismatched.verificationFailed)
        assertTrue(mismatched.requiresReview)
        assertNull(mismatched.vault)
    }

    @Test
    fun unexpectedWriterExceptionAfterARealSaveIsConservativelyUncertain() {
        val (file, updated) = prepared()
        val result = persistAccountCandidate(updated, file, password, save = { vault, destination, supplied ->
            assertEquals(VaultSaveOutcome.COMMITTED, vault.save(destination, supplied))
            throw IOException("synthetic private diagnostic")
        })
        assertEquals(VaultSaveOutcome.UNCERTAIN, result.outcome)
        assertTrue(result.requiresReview)
        assertFalse(result.message.contains("synthetic private diagnostic"))
        assertEquals(updated.entries, Vault.load(file, password).entries)
    }

    @Test
    fun cancellationIsPropagatedWithoutPretendingThatACommittedWriteRolledBack() {
        val (file, updated) = prepared()
        val original = file.readBytes()
        assertThrows(CancellationException::class.java) {
            persistAccountCandidate(updated, file, password, save = { _, _, _ -> throw CancellationException() })
        }
        assertArrayEquals(original, file.readBytes())
        assertThrows(CancellationException::class.java) {
            persistAccountCandidate(updated, file, password, load = { _, _ -> throw CancellationException() })
        }
        assertEquals(updated.entries, Vault.load(file, password).entries)
    }
}
