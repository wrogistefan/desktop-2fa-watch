package org.desktop2fa.desktop.watch.transfer

import kotlinx.serialization.json.*
import org.desktop2fa.desktop.watch.model.*
import org.desktop2fa.desktop.watch.vault.VaultSaveOutcome
import org.junit.Assert.*
import org.junit.Test

class TransferProtocolTest {
    private val sid = "a".repeat(32)
    private val batch = TransferOffer.parse("""{"v":1,"type":"OFFER","transfer_id":"11111111111111111111111111111111","accounts":[{"item_id":"22222222222222222222222222222222","kind":"totp","issuer":"synthetic","account":"synthetic account","secret":"AA","algorithm":"SHA1","digits":6,"period":30}]}""".toByteArray())
    private class Clock { var time = 0L }
    private fun protocol(clock: Clock = Clock(), expiry: Long = 900_000) = TransferProtocol(sid, expiry) { clock.time }
    private fun List<TransferEffect>.messages(): List<JsonObject> = filterIsInstance<TransferEffect.Send>().map {
        Json.parseToJsonElement(it.payload().decodeToString()).jsonObject
    }
    private fun List<TransferEffect>.types() = messages().map { it["type"]!!.jsonPrimitive.content }
    private fun List<TransferEffect>.code() = messages().first()["code"]!!.jsonPrimitive.content
    private fun ready(p: TransferProtocol) {
        assertEquals(listOf("HELLO"), p.authenticated().types())
        assertTrue(p.receive(TransferPeerMessage.Ready(sid)).isEmpty())
    }
    private fun reviewing(p: TransferProtocol): TransferAdditionCandidate {
        ready(p)
        val command = p.receive(TransferPeerMessage.Offer(batch)).single() as TransferEffect.Prepare
        val preparation = TransferPlanner.prepare(VaultData(2), batch) as TransferPreparation.Review
        assertEquals(listOf("REVIEW"), p.prepared(command.token, preparation).types())
        return preparation.candidate
    }
    @Test fun authenticatedHelloAndMatchingReadyPrecedeAnyOffer() {
        val p = protocol(); assertTrue(p.receive(TransferPeerMessage.Offer(batch)).isEmpty())
        assertEquals(listOf("HELLO"), p.authenticated().types())
        val failure = p.receive(TransferPeerMessage.Offer(batch))
        assertEquals("INVALID_MESSAGE", failure.code()); assertTrue(failure.contains(TransferEffect.EndSession))
        val other = protocol(); other.authenticated()
        assertEquals("INVALID_MESSAGE", other.receive(TransferPeerMessage.Ready("b".repeat(32))).code())
    }
    @Test fun localExactReviewApprovalAndConfirmedSaveProduceResult() {
        val p = protocol(); val candidate = reviewing(p)
        assertTrue(p.approve(TransferAdditionCandidate(VaultData(2), emptyList(), candidate.receipt)).isEmpty())
        val save = p.approve(candidate).single() as TransferEffect.Save
        assertSame(candidate, save.candidate)
        val result = p.committed(save.token, TransferCommitResult.Saved(candidate.receipt, null, false))
        assertEquals(listOf("RESULT"), result.types()); assertEquals(TransferProtocol.Phase.SAVED, p.phase)
        val again = p.receive(TransferPeerMessage.Status(batch.transferId, batch.sha256)).single() as TransferEffect.Lookup
        assertEquals(listOf("RESULT"), p.lookupCompleted(again.token, candidate.receipt).types())
        assertEquals("TOO_LATE", p.cancel().code())
    }
    @Test fun statusUnknownAllowsOnlyIdenticalOfferAndNeverSaves() {
        val p = protocol(); ready(p)
        val lookup = p.receive(TransferPeerMessage.Status(batch.transferId, batch.sha256)).single() as TransferEffect.Lookup
        assertEquals(listOf("UNKNOWN"), p.lookupCompleted(lookup.token, null).types())
        assertTrue(p.receive(TransferPeerMessage.Offer(batch)).single() is TransferEffect.Prepare)
        val bad = protocol(); ready(bad)
        val query = bad.receive(TransferPeerMessage.Status(batch.transferId, "f".repeat(64))).single() as TransferEffect.Lookup
        bad.lookupCompleted(query.token, null)
        assertEquals("TX_CONFLICT", bad.receive(TransferPeerMessage.Offer(batch)).code())
    }
    @Test fun savedReceiptStatusAndPrepareReplayNeedNoNewApproval() {
        val receipt = (TransferPlanner.prepare(VaultData(2), batch) as TransferPreparation.Review).candidate.receipt
        val p = protocol(); ready(p)
        val query = p.receive(TransferPeerMessage.Status(batch.transferId, batch.sha256)).single() as TransferEffect.Lookup
        assertEquals(listOf("RESULT"), p.lookupCompleted(query.token, receipt).types())
        val retry = protocol(); ready(retry)
        val prepare = retry.receive(TransferPeerMessage.Offer(batch)).single() as TransferEffect.Prepare
        assertEquals(listOf("RESULT"), retry.prepared(prepare.token, TransferPreparation.AlreadySaved(receipt)).types())
    }
    @Test fun duplicateOfferCannotReplaceReviewOrStartParallelPreparation() {
        val p = protocol(); reviewing(p)
        assertEquals("BUSY", p.receive(TransferPeerMessage.Offer(batch)).code())
        assertEquals(TransferProtocol.Phase.CLOSED, p.phase)
    }
    @Test fun remoteCancelBeforeSaveEndsCredentialAndDropsPendingReview() {
        val p = protocol(); val candidate = reviewing(p)
        val cancelled = p.receive(TransferPeerMessage.Cancel(batch.transferId, "user"))
        assertEquals(listOf("CANCELLED"), cancelled.types()); assertTrue(cancelled.contains(TransferEffect.EndSession))
        assertTrue(p.approve(candidate).isEmpty()); assertTrue(p.authenticated().isEmpty())
    }
    @Test fun savingCancelWaitsForGateAndActualPublication() {
        for (late in listOf(false, true)) {
            val p = protocol(); val candidate = reviewing(p)
            val save = p.approve(candidate).single() as TransferEffect.Save
            assertTrue(p.cancel().single() is TransferEffect.CancelSave)
            val signal = p.cancellationSignal(save.token, if (late) TransferCancellation.TOO_LATE else TransferCancellation.CANCELLED)
            assertEquals(if (late) listOf("ERROR") else emptyList<String>(), signal.types())
            if (late) assertEquals("TOO_LATE", signal.code())
            val result = if (late) TransferCommitResult.Saved(candidate.receipt, null, false) else TransferCommitResult.Cancelled
            assertEquals(listOf(if (late) "RESULT" else "CANCELLED"), p.committed(save.token, result).types())
        }
    }
    @Test fun priorDurableReceiptWinsOverCancellationSignal() {
        val p = protocol(); val candidate = reviewing(p); val save = p.approve(candidate).single() as TransferEffect.Save
        p.cancel(); assertTrue(p.cancellationSignal(save.token, TransferCancellation.CANCELLED).isEmpty())
        assertEquals(listOf("RESULT"), p.committed(save.token, TransferCommitResult.Saved(candidate.receipt, null, false)).types())
    }
    @Test fun staleSaveReplansAndRequiresNewCandidateApproval() {
        val p = protocol(); val candidate = reviewing(p)
        val save = p.approve(candidate).single() as TransferEffect.Save
        val prepare = p.committed(save.token, TransferCommitResult.Stale).single() as TransferEffect.Prepare
        assertSame(batch, prepare.offer)
        val replacement = TransferPlanner.prepare(VaultData(2), batch) as TransferPreparation.Review
        assertEquals(listOf("REVIEW"), p.prepared(prepare.token, replacement).types())
        assertTrue(p.approve(candidate).isEmpty()); assertTrue(p.approve(replacement.candidate).single() is TransferEffect.Save)
    }
    @Test fun disconnectionDropsPendingAndOldCallbacksCannotPublishIntoReconnect() {
        val p = protocol(); val candidate = reviewing(p)
        val save = p.approve(candidate).single() as TransferEffect.Save
        assertTrue(p.disconnected().any { it is TransferEffect.DropPending })
        ready(p)
        assertTrue(p.committed(save.token, TransferCommitResult.Saved(candidate.receipt, null, false)).isEmpty())
        assertEquals("INVALID_MESSAGE", p.receive(TransferPeerMessage.Offer(batch)).code())
    }
    @Test fun reconnectRequiresStatusAndFreshConsentAfterUnknown() {
        val p = protocol(); reviewing(p); p.disconnected(); ready(p)
        val query = p.receive(TransferPeerMessage.Status(batch.transferId, batch.sha256)).single() as TransferEffect.Lookup
        assertEquals(listOf("UNKNOWN"), p.lookupCompleted(query.token, null).types())
        val prepare = p.receive(TransferPeerMessage.Offer(batch)).single() as TransferEffect.Prepare
        val fresh = TransferPlanner.prepare(VaultData(2), batch) as TransferPreparation.Review
        assertEquals(listOf("REVIEW"), p.prepared(prepare.token, fresh).types())
    }
    @Test fun lockAndUnconfirmedWriteNeverEmitSuccessOrRestoreOldOwner() {
        val p = protocol(); val candidate = reviewing(p); val save = p.approve(candidate).single() as TransferEffect.Save
        assertEquals("VAULT_LOCKED", p.locked().code())
        assertTrue(p.committed(save.token, TransferCommitResult.Saved(candidate.receipt, null, false)).isEmpty())
        val other = protocol(); val review = reviewing(other); val attempt = other.approve(review).single() as TransferEffect.Save
        assertEquals("STORAGE_ERROR", other.committed(attempt.token, TransferCommitResult.Unconfirmed(VaultSaveOutcome.UNCERTAIN)).code())
    }
    @Test fun reviewHeartbeatAndIdleTimeoutUseMonotonicBoundaries() {
        val clock = Clock(); val p = protocol(clock); reviewing(p)
        assertEquals(10_000L, p.nextDeadline())
        clock.time = 10_000; assertEquals(listOf("PING"), p.tick().types())
        clock.time = 20_000; assertTrue(p.receive(TransferPeerMessage.Pong).isEmpty())
        clock.time = 49_999; assertEquals(listOf("PING"), p.tick().types())
        clock.time = 50_000; assertTrue(p.tick().contains(TransferEffect.CloseChannel))
        assertEquals(TransferProtocol.Phase.DISCONNECTED, p.phase)
    }
    @Test fun sessionExpiryRemainsScheduledAcrossDisconnection() {
        val clock = Clock(); val p = protocol(clock, 100); ready(p); p.disconnected()
        assertEquals(100L, p.nextDeadline()); clock.time = 100
        assertTrue(p.tick().contains(TransferEffect.EndSession)); assertNull(p.nextDeadline())
    }
    @Test fun validPongsCannotExtendFiveMinuteReviewBudget() {
        val clock = Clock(); val p = protocol(clock); reviewing(p)
        for (time in 10_000L until 300_000L step 10_000L) { clock.time = time; p.receive(TransferPeerMessage.Pong) }
        clock.time = 300_000
        assertEquals("SESSION_EXPIRED", p.tick().code())
    }
    @Test fun staleReplanningDoesNotRenewReviewBudget() {
        val clock = Clock(); val p = protocol(clock); val candidate = reviewing(p)
        for (time in 10_000L until 290_000L step 10_000L) { clock.time = time; p.receive(TransferPeerMessage.Pong) }
        clock.time = 290_000; val save = p.approve(candidate).single() as TransferEffect.Save
        clock.time = 299_000; val prepare = p.committed(save.token, TransferCommitResult.Stale).single() as TransferEffect.Prepare
        val next = TransferPlanner.prepare(VaultData(2), batch)
        p.prepared(prepare.token, next); clock.time = 300_000
        assertEquals("SESSION_EXPIRED", p.tick().code())
    }
    @Test fun cancelDuringAuthenticatedLookupWaitsForActualReceipt() {
        val receipt = (TransferPlanner.prepare(VaultData(2), batch) as TransferPreparation.Review).candidate.receipt
        for (found in listOf(false, true)) {
            val p = protocol(); ready(p)
            val query = p.receive(TransferPeerMessage.Status(batch.transferId, batch.sha256)).single() as TransferEffect.Lookup
            assertTrue(p.cancel().isEmpty())
            val response = p.lookupCompleted(query.token, if (found) receipt else null)
            assertEquals(if (found) listOf("ERROR", "RESULT") else listOf("CANCELLED"), response.types())
            if (found) assertEquals("TOO_LATE", response.code())
        }
    }
    @Test fun cancelDuringPreparationCannotDenyAnAlreadyStoredReceipt() {
        val receipt = (TransferPlanner.prepare(VaultData(2), batch) as TransferPreparation.Review).candidate.receipt
        for (saved in listOf(false, true)) {
            val p = protocol(); ready(p)
            val prepare = p.receive(TransferPeerMessage.Offer(batch)).single() as TransferEffect.Prepare
            assertTrue(p.cancel().isEmpty())
            val result = if (saved) TransferPreparation.AlreadySaved(receipt) else TransferPlanner.prepare(VaultData(2), batch)
            assertEquals(if (saved) listOf("ERROR", "RESULT") else listOf("CANCELLED"), p.prepared(prepare.token, result).types())
        }
    }
    @Test fun storageCallbackMustMatchRequestedReceiptAndOperationToken() {
        val p = protocol(); ready(p)
        val query = p.receive(TransferPeerMessage.Status(batch.transferId, batch.sha256)).single() as TransferEffect.Lookup
        assertTrue(p.lookupCompleted(Any(), null).isEmpty())
        val wrong = TransferReceipt("3".repeat(32), batch.sha256, emptyList(), emptyList())
        assertEquals("STORAGE_ERROR", p.lookupCompleted(query.token, wrong).code())
    }
}
