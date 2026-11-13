package org.desktop2fa.desktop.watch.transfer

import kotlinx.serialization.json.*
import org.desktop2fa.desktop.watch.model.*
import org.desktop2fa.desktop.watch.vault.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TransferCommitTest {
    @get:Rule val temporary = TemporaryFolder()
    private val password = "Synthetic-Transfer-Commit-42!"
    private fun offer() = TransferOffer.parse(buildJsonObject {
        put("v",1); put("type","OFFER"); put("transfer_id","1".repeat(32))
        put("accounts", buildJsonArray { add(buildJsonObject {
            put("item_id","2".repeat(32)); put("kind","totp"); put("issuer","synthetic")
            put("account","synthetic account"); put("secret","AA"); put("algorithm","SHA1"); put("digits",6); put("period",30)
        }) })
    }.toString().toByteArray())
    private fun plan(data: VaultData = VaultData(2)) =
        (TransferPlanner.prepare(data, offer()) as TransferPreparation.Review).candidate
    private val unused = File("synthetic-unused-transfer.d2fa")
    private open class MemoryIO(initial: VaultData) : TransferCommitIO() {
        var current = Vault(initial)
        var writes = 0
        override fun load(file: File,password: String) = current
        override fun inspect(file: File) = VaultPresence.PRESENT
        override fun mainPresent(file: File) = true
        override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
            writes++; current = vault; return VaultSaveOutcome.COMMITTED
        }
    }
    @Test fun accountsAndReceiptAreOneEncryptedPublicationAndReplayDoesNotWrite() {
        val file = File(temporary.root,"synthetic.d2fa")
        val original = Vault(VaultData(2)); original.saveNew(file,password)
        val candidate = plan(original.data)
        val io = object : TransferCommitIO() {
            var writes = 0
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                writes++; return super.save(vault,file,password)
            }
        }
        val gate = TransferSaveGate()
        val saved = commitApprovedTransfer(candidate,file,password,gate,io) as TransferCommitResult.Saved
        assertFalse(saved.requiresStorageReview); assertEquals(candidate.receipt,saved.receipt)
        assertEquals(candidate.vault().data,Vault.load(file,password).data)
        assertEquals(TransferCancellation.TOO_LATE,gate.cancel())
        val before = file.readBytes()
        val replay = commitApprovedTransfer(candidate,file,password,TransferSaveGate(),io) as TransferCommitResult.Saved
        assertEquals(candidate.receipt,replay.receipt); assertEquals(1,io.writes)
        assertArrayEquals(before,file.readBytes())
    }
    @Test fun allDuplicateApprovalStillPublishesAnEncryptedReceipt() {
        val file = File(temporary.root,"duplicates.d2fa")
        val original = Vault(VaultData(2,mutableListOf(TotpEntry("other","label","AA"))))
        original.saveNew(file,password)
        val candidate = plan(original.data)
        assertTrue(candidate.receipt.addedItemIds.isEmpty())
        val result = commitApprovedTransfer(candidate,file,password,TransferSaveGate()) as TransferCommitResult.Saved
        assertEquals(original.data.entries,result.vault!!.data.entries)
        assertEquals(listOf(candidate.receipt),Vault.load(file,password).data.transferReceipts)
    }
    @Test fun cancelledApprovalNeverCallsStorageAndCannotStartAgain() {
        val gate = TransferSaveGate(); assertEquals(TransferCancellation.CANCELLED,gate.cancel())
        val io = object : MemoryIO(VaultData(2)) {
            override fun load(file: File,password: String): Vault = throw AssertionError("Cancelled request read storage")
        }
        assertSame(TransferCommitResult.Cancelled,commitApprovedTransfer(plan(),unused,password,gate,io))
        assertFalse(gate.begin()); assertEquals(0,io.writes)
    }
    @Test fun cancellationDuringRevalidationWinsBeforePublication() {
        val gate = TransferSaveGate()
        val io = object : MemoryIO(VaultData(2)) {
            override fun load(file: File,password: String): Vault {
                assertEquals(TransferCancellation.CANCELLED,gate.cancel()); return current
            }
        }
        assertSame(TransferCommitResult.Cancelled,commitApprovedTransfer(plan(),unused,password,gate,io))
        assertEquals(0,io.writes)
    }
    @Test fun cancellationAtWriterEntryIsTooLateAndReceiptStillCommits() {
        val gate = TransferSaveGate()
        val io = object : MemoryIO(VaultData(2)) {
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                assertEquals(TransferCancellation.TOO_LATE,gate.cancel())
                return super.save(vault,file,password)
            }
        }
        assertTrue(commitApprovedTransfer(plan(),unused,password,gate,io) is TransferCommitResult.Saved)
        assertEquals(1,io.writes); assertFalse(gate.begin())
    }
    @Test fun staleSnapshotRequiresNewReviewWithoutDroppingNewerEntriesOrReceipts() {
        val candidate = plan()
        val newer = VaultData(2,mutableListOf(TotpEntry("synthetic newer","other","AE")))
        val io = MemoryIO(newer)
        val gate = TransferSaveGate()
        assertSame(TransferCommitResult.Stale,commitApprovedTransfer(candidate,unused,password,gate,io))
        assertEquals(newer,io.current.data); assertEquals(0,io.writes); assertFalse(gate.begin())
    }
    @Test fun exceptionAfterPublicationUsesActualReceiptInsteadOfAssumingFailure() {
        val io = object : MemoryIO(VaultData(2)) {
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                super.save(vault,file,password); throw IOException("synthetic post-publication failure")
            }
        }
        assertTrue(commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) is TransferCommitResult.Saved)
        assertEquals(1,io.writes)
    }
    @Test fun unconfirmedSaveNeverReturnsProposedReceiptOrAutomaticallyRetries() {
        for (known in listOf(false,true)) {
            val io = object : MemoryIO(VaultData(2)) {
                var reads = 0
                override fun load(file: File,password: String): Vault {
                    if (++reads > 1) throw IOException("synthetic verification unavailable")
                    return current
                }
                override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                    writes++
                    if (!known) throw IOException("synthetic uncertain publication")
                    return VaultSaveOutcome.COMMITTED
                }
            }
            val result = commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) as TransferCommitResult.Unconfirmed
            assertEquals(if (known) VaultSaveOutcome.COMMITTED else VaultSaveOutcome.UNCERTAIN,result.outcome)
            assertEquals(1,io.writes)
        }
    }
    @Test fun preCommitFailureAndDirtyStorageCannotClaimSuccessfulResult() {
        val io = object : MemoryIO(VaultData(2)) {
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                writes++; return VaultSaveOutcome.NOT_COMMITTED
            }
        }
        val result = commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) as TransferCommitResult.Unconfirmed
        assertEquals(VaultSaveOutcome.NOT_COMMITTED,result.outcome); assertEquals(1,io.writes)
        val dirty = object : MemoryIO(VaultData(2)) { override fun inspect(file: File) = VaultPresence.RECOVERY_REQUIRED }
        val gate = TransferSaveGate()
        assertTrue(commitApprovedTransfer(plan(),unused,password,gate,dirty) is TransferCommitResult.Unconfirmed)
        assertEquals(0,dirty.writes); assertFalse(gate.begin())
    }
    @Test fun verifiedReceiptWithRecoveryArtifactsRequiresStorageReview() {
        val io = object : MemoryIO(VaultData(2)) {
            override fun inspect(file: File) = if (writes == 0) VaultPresence.PRESENT else VaultPresence.RECOVERY_REQUIRED
        }
        val result = commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) as TransferCommitResult.Saved
        assertTrue(result.requiresStorageReview); assertNull(result.vault)
    }
    @Test fun wrongPasswordPreservesEncryptedSourceAndCannotCreateReceipt() {
        val file = File(temporary.root,"wrong-password.d2fa")
        val baseline = Vault(VaultData(2)); baseline.saveNew(file,password)
        val bytes = file.readBytes(); val gate = TransferSaveGate()
        try { commitApprovedTransfer(plan(),file,"synthetic wrong password",gate); fail("Expected authentication failure") }
        catch (_: InvalidPasswordException) {}
        assertArrayEquals(bytes,file.readBytes()); assertFalse(gate.begin())
    }
    @Test fun missingOrUninspectableMainCannotProduceResultFromAnEarlierRead() {
        for (missing in listOf(true,false)) {
            val io = object : MemoryIO(VaultData(2)) {
                override fun inspect(file: File): VaultPresence {
                    if (writes == 0) return VaultPresence.PRESENT
                    if (missing) return VaultPresence.MISSING
                    throw IOException("synthetic metadata unavailable")
                }
            }
            val result = commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) as TransferCommitResult.Unconfirmed
            assertEquals(VaultSaveOutcome.COMMITTED,result.outcome); assertEquals(1,io.writes)
        }
        val orphan = object : MemoryIO(VaultData(2)) {
            override fun inspect(file: File) = if (writes == 0) VaultPresence.PRESENT else VaultPresence.RECOVERY_REQUIRED
            override fun mainPresent(file: File) = false
        }
        assertTrue(commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),orphan) is TransferCommitResult.Unconfirmed)
    }

    @Test fun receiptWithoutTheCompleteExpectedAccountsCannotConfirmANewSave() {
        val io = object : MemoryIO(VaultData(2)) {
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                writes++; current = Vault(VaultData(2,transferReceipts=vault.data.transferReceipts))
                return VaultSaveOutcome.COMMITTED
            }
        }
        assertTrue(commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) is TransferCommitResult.Unconfirmed)
    }
    @Test fun knownRecoveryWarningSurvivesALaterCleanMetadataInspection() {
        val io = object : MemoryIO(VaultData(2)) {
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                super.save(vault,file,password); return VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS
            }
        }
        val result = commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) as TransferCommitResult.Saved
        assertTrue(result.requiresStorageReview); assertNull(result.vault)
    }

    @Test fun writerCannotChangeVerificationExpectationsByMutatingItsInput() {
        val io = object : MemoryIO(VaultData(2)) {
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                vault.data.entries.clear()
                return super.save(vault,file,password)
            }
        }
        assertTrue(commitApprovedTransfer(plan(),unused,password,TransferSaveGate(),io) is TransferCommitResult.Unconfirmed)
    }
    @Test fun finishedApprovalCannotBeReusedOrReportedAsACancelledWrite() {
        val gate = TransferSaveGate()
        val io = object : MemoryIO(VaultData(2)) {
            override fun save(vault: Vault,file: File,password: String): VaultSaveOutcome {
                writes++; return VaultSaveOutcome.UNCERTAIN
            }
        }
        assertTrue(commitApprovedTransfer(plan(),unused,password,gate,io) is TransferCommitResult.Unconfirmed)
        assertTrue(commitApprovedTransfer(plan(),unused,password,gate,io) is TransferCommitResult.Unconfirmed)
        assertEquals(1,io.writes); assertEquals(TransferCancellation.TOO_LATE,gate.cancel())
    }

}
