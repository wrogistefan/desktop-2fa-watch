package org.desktop2fa.desktop.watch.transfer

import org.desktop2fa.desktop.watch.model.TransferReceipt

/** Commands executed by one serialized UI/session owner, never by a remote message directly. */
internal sealed interface TransferEffect {
    class Send(payload: ByteArray) : TransferEffect {
        private val owned = payload.copyOf()
        fun payload() = owned.copyOf()
        override fun toString() = "Send(redacted)"
    }
    class Lookup(val token: Any, val transferId: String, val sha256: String) : TransferEffect
    class Prepare(val token: Any, val offer: TransferOffer) : TransferEffect {
        override fun toString() = "Prepare(redacted)"
    }
    class Save(val token: Any, val candidate: TransferAdditionCandidate) : TransferEffect {
        override fun toString() = "Save(redacted)"
    }
    /** Cancel the active storage gate/discard its draft; do not announce CANCELLED on disconnection. */
    class DropPending(val token: Any?) : TransferEffect
    class CancelSave(val token: Any) : TransferEffect
    data object CloseChannel : TransferEffect
    data object EndSession : TransferEffect
}

/**
 * One credential/session, one batch, serialized events and monotonic time.
 * Only the authenticated native transport may call authenticated(). Effects are
 * commands, not proof that storage or I/O succeeded. No secrets are persisted.
 */
internal class TransferProtocol(
    private val sessionId: String,
    private val sessionDeadline: Long,
    private val now: () -> Long,
) {
    enum class Phase { NEW, READY, IDLE, LOOKUP, PREPARING, REVIEW, SAVING, SAVED, DISCONNECTED, CLOSED }
    var phase = Phase.NEW
        private set
    private var boundId: String? = null
    private var boundDigest: String? = null
    private var operation: Any? = null
    private var offer: TransferOffer? = null
    private var candidate: TransferAdditionCandidate? = null
    private var resumed = false
    private var statusRequired = false
    private var receiveDeadline = Long.MAX_VALUE
    private var reviewCutoff = Long.MAX_VALUE
    private var reviewDeadline = Long.MAX_VALUE
    private var nextPing = Long.MAX_VALUE
    private var cancelRequested = false

    init { require(Regex("[0-9a-f]{32}").matches(sessionId)) }
    /** Earliest monotonic wakeup; callers need no polling loop. */
    fun nextDeadline(): Long? = when (phase) {
        Phase.CLOSED -> null
        Phase.NEW, Phase.DISCONNECTED -> sessionDeadline
        else -> minOf(sessionDeadline, receiveDeadline, reviewDeadline, nextPing)
    }
    private fun send(bytes: ByteArray) = TransferEffect.Send(bytes)
    private fun finish(code: TransferError? = null): List<TransferEffect> {
        if (phase == Phase.CLOSED) return emptyList()
        val effects = mutableListOf<TransferEffect>()
        if (code != null && phase != Phase.DISCONNECTED && phase != Phase.NEW)
            effects += send(TransferWire.error(code, boundId))
        effects += TransferEffect.DropPending(operation)
        effects += TransferEffect.CloseChannel
        effects += TransferEffect.EndSession
        phase = Phase.CLOSED; operation = null; offer = null; candidate = null
        reviewDeadline = Long.MAX_VALUE; nextPing = Long.MAX_VALUE
        return effects
    }
    private fun expired(): List<TransferEffect>? {
        if (phase == Phase.CLOSED) return emptyList()
        if (now() >= sessionDeadline || now() >= reviewDeadline) return finish(TransferError.SESSION_EXPIRED)
        if (phase !in setOf(Phase.NEW, Phase.DISCONNECTED) && now() >= receiveDeadline) return disconnected()
        return null
    }
    fun authenticated(): List<TransferEffect> {
        expired()?.let { return it }
        if (phase != Phase.NEW && phase != Phase.DISCONNECTED) return finish(TransferError.INVALID_MESSAGE)
        statusRequired = resumed
        resumed = true
        phase = Phase.READY
        receiveDeadline = now() + 15_000
        return listOf(send(TransferWire.hello(sessionId)))
    }
    private fun bind(id: String, digest: String): Boolean {
        if (boundId == null) { boundId = id; boundDigest = digest; return true }
        return boundId == id && boundDigest == digest
    }
    fun receive(message: TransferPeerMessage): List<TransferEffect> {
        expired()?.let { return it }
        if (phase in setOf(Phase.NEW, Phase.DISCONNECTED, Phase.CLOSED)) return emptyList()
        if (phase == Phase.READY) {
            if (message !is TransferPeerMessage.Ready || message.sessionId != sessionId)
                return finish(TransferError.INVALID_MESSAGE)
            phase = Phase.IDLE; receiveDeadline = now() + 15_000
            return emptyList()
        }
        if (message is TransferPeerMessage.Ready) return finish(TransferError.INVALID_MESSAGE)
        if (message is TransferPeerMessage.Error) return finish()
        receiveDeadline = now() + if (phase == Phase.REVIEW) 30_000 else 15_000
        if (message === TransferPeerMessage.Ping) return listOf(send(TransferWire.pong()))
        if (message === TransferPeerMessage.Pong) return emptyList()
        if (message is TransferPeerMessage.Cancel) {
            if (boundId != message.transferId) return finish(TransferError.INVALID_MESSAGE)
            return cancel()
        }
        return when (message) {
            is TransferPeerMessage.Status -> {
                if (!bind(message.transferId, message.sha256)) return finish(TransferError.TX_CONFLICT)
                if (phase !in setOf(Phase.IDLE, Phase.SAVED)) return finish(TransferError.BUSY)
                phase = Phase.LOOKUP; operation = Any(); cancelRequested = false
                listOf(TransferEffect.Lookup(operation!!, message.transferId, message.sha256))
            }
            is TransferPeerMessage.Offer -> {
                if (statusRequired) return finish(TransferError.INVALID_MESSAGE)
                if (!bind(message.offer.transferId, message.offer.sha256)) return finish(TransferError.TX_CONFLICT)
                if (phase != Phase.IDLE) return finish(TransferError.BUSY)
                phase = Phase.PREPARING; offer = message.offer; operation = Any(); cancelRequested = false
                listOf(TransferEffect.Prepare(operation!!, message.offer))
            }
            else -> finish(TransferError.INVALID_MESSAGE)
        }
    }
    fun lookupCompleted(token: Any, receipt: TransferReceipt?): List<TransferEffect> {
        expired()?.let { return it }
        if (phase != Phase.LOOKUP || operation !== token) return emptyList()
        operation = null; statusRequired = false
        if (receipt == null) {
            if (cancelRequested) return listOf(send(TransferWire.cancelled(requireNotNull(boundId)))) + finish()
            phase = Phase.IDLE; return listOf(send(TransferWire.unknown(requireNotNull(boundId))))
        }
        if (receipt.transferId != boundId || receipt.offerSha256 != boundDigest) return finish(TransferError.STORAGE_ERROR)
        val saved = TransferCommitResult.Saved(receipt, null, false); phase = Phase.SAVED
        val cancelledReplay = cancelRequested; cancelRequested = false
        return (if (cancelledReplay) listOf(send(TransferWire.error(TransferError.TOO_LATE, boundId))) else emptyList()) +
            send(TransferWire.result(saved))
    }
    fun prepared(token: Any, preparation: TransferPreparation): List<TransferEffect> {
        expired()?.let { return it }
        if (phase != Phase.PREPARING || operation !== token) return emptyList()
        if (preparation is TransferPreparation.AlreadySaved) {
            val receipt = preparation.receipt
            if (receipt.transferId != boundId || receipt.offerSha256 != boundDigest) return finish(TransferError.STORAGE_ERROR)
            val saved = TransferCommitResult.Saved(receipt, null, false); phase = Phase.SAVED
            operation = null; offer = null
            val cancelledReplay = cancelRequested; cancelRequested = false
            return (if (cancelledReplay) listOf(send(TransferWire.error(TransferError.TOO_LATE, boundId))) else emptyList()) +
                send(TransferWire.result(saved))
        }
        if (cancelRequested) return listOf(send(TransferWire.cancelled(requireNotNull(boundId)))) + finish()
        val planned = (preparation as TransferPreparation.Review).candidate
        if (planned.receipt.transferId != boundId || planned.receipt.offerSha256 != boundDigest) return finish(TransferError.STORAGE_ERROR)
        candidate = planned; phase = Phase.REVIEW
        operation = null
        // Replanning must not extend the original local review budget.
        if (reviewCutoff == Long.MAX_VALUE) reviewCutoff = minOf(now() + 300_000, sessionDeadline)
        reviewDeadline = reviewCutoff
        if (now() >= reviewDeadline) return finish(TransferError.SESSION_EXPIRED)
        receiveDeadline = now() + 30_000; nextPing = now() + 10_000
        return listOf(send(TransferWire.review(planned)))
    }
    /** Candidate identity binds the local tap to exactly the rendered REVIEW. */
    fun approve(review: TransferAdditionCandidate): List<TransferEffect> {
        expired()?.let { return it }
        if (phase != Phase.REVIEW || candidate !== review) return emptyList()
        phase = Phase.SAVING; operation = Any(); candidate = null
        nextPing = Long.MAX_VALUE; reviewDeadline = Long.MAX_VALUE
        receiveDeadline = now() + 15_000
        return listOf(TransferEffect.Save(operation!!, review))
    }
    fun cancel(): List<TransferEffect> {
        expired()?.let { return it }
        if (phase == Phase.SAVING) {
            if (cancelRequested) return emptyList()
            cancelRequested = true
            return listOf(TransferEffect.CancelSave(requireNotNull(operation)))
        }
        // An in-flight authenticated read may discover a durable receipt. Do not
        // claim terminal cancellation until that read resolves (or the channel closes).
        if (phase == Phase.LOOKUP || phase == Phase.PREPARING) {
            cancelRequested = true
            return emptyList()
        }
        if (phase == Phase.SAVED) return listOf(send(TransferWire.error(TransferError.TOO_LATE, boundId)))
        if (boundId == null) return finish()
        val response = send(TransferWire.cancelled(boundId!!))
        return listOf(response) + finish()
    }
    /** A gate response is not a terminal CANCELLED; await the actual storage result. */
    fun cancellationSignal(token: Any, result: TransferCancellation): List<TransferEffect> {
        expired()?.let { return it }
        if (phase != Phase.SAVING || operation !== token || !cancelRequested) return emptyList()
        return if (result == TransferCancellation.TOO_LATE)
            listOf(send(TransferWire.error(TransferError.TOO_LATE, boundId))) else emptyList()
    }
    fun committed(token: Any, result: TransferCommitResult): List<TransferEffect> {
        expired()?.let { return it }
        if (phase != Phase.SAVING || operation !== token) return emptyList()
        when (result) {
            is TransferCommitResult.Saved -> {
                if (result.receipt.transferId != boundId || result.receipt.offerSha256 != boundDigest) return finish(TransferError.STORAGE_ERROR)
                offer = null; operation = null; phase = Phase.SAVED
                val sent = listOf(send(TransferWire.result(result)))
                return if (result.requiresStorageReview) sent + finish() else sent
            }
            TransferCommitResult.Cancelled -> return listOf(send(TransferWire.cancelled(boundId!!))) + finish()
            is TransferCommitResult.Unconfirmed -> return finish(TransferError.STORAGE_ERROR)
            TransferCommitResult.Stale -> {
                if (cancelRequested) return listOf(send(TransferWire.cancelled(boundId!!))) + finish()
                phase = Phase.PREPARING; operation = Any()
                return listOf(TransferEffect.Prepare(operation!!, requireNotNull(offer)))
            }
        }
    }
    fun failed(token: Any, code: TransferError, itemIds: List<String>? = null): List<TransferEffect> {
        expired()?.let { return it }
        if (operation !== token || phase !in setOf(Phase.LOOKUP, Phase.PREPARING, Phase.SAVING)) return emptyList()
        val response = send(TransferWire.error(code, boundId, itemIds))
        return listOf(response) + finish()
    }
    /** Parser/state failures end the attempt; never continue parsing a corrected batch. */
    fun invalid(code: TransferError): List<TransferEffect> = expired() ?: finish(code)
    fun locked(): List<TransferEffect> = finish(TransferError.VAULT_LOCKED)
    fun disconnected(): List<TransferEffect> {
        if (phase == Phase.CLOSED || phase == Phase.DISCONNECTED) return emptyList()
        val drop = TransferEffect.DropPending(operation)
        phase = Phase.DISCONNECTED; operation = null; candidate = null; offer = null
        cancelRequested = false; reviewDeadline = Long.MAX_VALUE; nextPing = Long.MAX_VALUE
        return listOf(drop, TransferEffect.CloseChannel)
    }
    fun tick(): List<TransferEffect> {
        expired()?.let { return it }
        if (phase == Phase.REVIEW && now() >= nextPing) {
            nextPing = now() + 10_000
            return listOf(send(TransferWire.ping()))
        }
        return emptyList()
    }
}
