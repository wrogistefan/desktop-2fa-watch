package org.desktop2fa.desktop.watch.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.desktop2fa.desktop.watch.transfer.*

/** Serialized UI-dispatcher adapter. The socket/native owner must acknowledge final closure. */
internal class VaultTransferOwner private constructor(
    private val session: VaultSession,
    private val token: Any,
    private val uiToken: Any,
    private val protocol: TransferProtocol,
    scope: CoroutineScope,
    private val checkDeadline: () -> Boolean,
) {
    private val output = Channel<TransferEffect>(16)
    private var pending: TransferEffect? = null
    private var needsRearm = false
    private var requestWaiting = false
    private var ended = false
    private var released = false
    private val observer: Job = scope.launch(start = CoroutineStart.LAZY) {
        try { session.transferChanges.collect { synchronize() } }
        finally { if (!ended) close() }
    }
    init { observer.invokeOnCompletion { output.close() } }
    var phase by mutableStateOf(protocol.phase)
        private set
    val review: TransferAdditionCandidate? get() = session.transferReview.takeIf {
        phase == TransferProtocol.Phase.REVIEW && session.transferOwnerError(token) == null
    }
    fun nextDeadline(): Long? = protocol.nextDeadline()
    /** Consumer must destroy native/socket resources on EndSession or end-of-channel, even on failure. */
    suspend fun nextOutput(): TransferEffect? = output.receiveCatching().getOrNull()
    private fun guard(): Boolean {
        if (ended) return false
        val error = session.transferOwnerError(token)
        if (!session.acceptsUiToken(uiToken) || !checkDeadline() || error != null) {
            execute(if (error == TransferError.STORAGE_ERROR) protocol.invalid(error) else protocol.locked())
            return false
        }
        return true
    }
    fun authenticated() {
        if (!guard()) return
        if (phase == TransferProtocol.Phase.DISCONNECTED) needsRearm = true
        // HELLO is not blocked by an old writer; the eventual STATUS read waits for it.
        execute(protocol.authenticated())
    }
    fun receive(payload: ByteArray) {
        if (!guard()) return
        val message = try { TransferWire.parseDesktop(payload) }
            catch (failure: TransferValidationFailure) { execute(protocol.invalid(failure.code)); return }
        execute(protocol.receive(message))
    }
    /** The Activity must count this local tap as user input before calling. */
    fun approve(candidate: TransferAdditionCandidate) { if (guard()) execute(protocol.approve(candidate)) }
    fun cancel() { if (guard()) execute(protocol.cancel()) }
    fun tick() { if (guard()) execute(protocol.tick()) }
    fun disconnected() { needsRearm = false; if (!ended) execute(protocol.disconnected()) }
    fun close() { if (!ended) execute(protocol.locked()) }
    private fun clearOutput() { while (output.tryReceive().isSuccess) { /* Drop obsolete channel messages. */ } }
    private fun emit(effect: TransferEffect) {
        if (output.trySend(effect).isFailure && !ended) {
            // A stopped consumer cannot accumulate unbounded metadata/work.
            clearOutput()
            execute(protocol.invalid(TransferError.BUSY))
        }
    }
    private fun execute(effects: List<TransferEffect>) {
        phase = protocol.phase
        if (effects.any { it === TransferEffect.CloseChannel || it === TransferEffect.EndSession }) clearOutput()
        for (effect in effects) when (effect) {
            is TransferEffect.Send, TransferEffect.CloseChannel -> emit(effect)
            TransferEffect.EndSession -> {
                emit(effect); ended = true; pending = null; output.close(); observer.cancel()
            }
            is TransferEffect.Lookup, is TransferEffect.Prepare, is TransferEffect.Save -> {
                pending = effect
                startRequest()
            }
            is TransferEffect.CancelSave -> {
                val signal = session.cancelTransfer(token)
                if (signal == null) fail(effect.token)
                else execute(protocol.cancellationSignal(effect.token, signal))
            }
            is TransferEffect.DropPending -> {
                pending = null; requestWaiting = false
                session.cancelTransfer(token)
            }
        }
    }
    private fun startRequest() {
        if (session.operationPending) { requestWaiting = true; return }
        requestWaiting = false
        if (needsRearm) {
            if (!session.resumeTransferOwner(token)) {
                execute(protocol.invalid(TransferError.STORAGE_ERROR)); return
            }
            needsRearm = false
        }
        when (val request = pending) {
            is TransferEffect.Lookup -> if (!session.queryTransferStatus(token, request.transferId, request.sha256)) fail(request.token)
            is TransferEffect.Prepare -> if (!session.prepareTransfer(token, request.offer)) fail(request.token)
            is TransferEffect.Save -> if (!session.approveTransfer(token, request.candidate)) fail(request.token)
            else -> Unit
        }
    }
    private fun fail(operation: Any) { execute(protocol.failed(operation, TransferError.STORAGE_ERROR)) }
    private fun synchronize() {
        if (!guard() || session.operationPending) return
        if (requestWaiting) { startRequest(); return }
        when (val request = pending) {
            is TransferEffect.Lookup -> when (val result = session.transferStatus) {
                is TransferStatusResult.Found -> { pending = null; execute(protocol.lookupCompleted(request.token, result.receipt)) }
                TransferStatusResult.Unknown -> { pending = null; execute(protocol.lookupCompleted(request.token, null)) }
                is TransferStatusResult.Failed -> { pending = null; execute(protocol.failed(request.token, result.code)) }
                null -> Unit
            }
            is TransferEffect.Prepare -> {
                session.transferReview?.let {
                    pending = null; execute(protocol.prepared(request.token, TransferPreparation.Review(it))); return
                }
                (session.transferResult as? TransferCommitResult.Saved)?.let {
                    pending = null; execute(protocol.prepared(request.token, TransferPreparation.AlreadySaved(it.receipt))); return
                }
                session.transferError?.let { pending = null; execute(protocol.failed(request.token, it)) }
            }
            is TransferEffect.Save -> session.transferResult?.let {
                pending = null; execute(protocol.committed(request.token, it))
            }
            else -> Unit
        }
    }
    /** Call only after closing native contexts, socket and network callbacks. UI dispatcher only. */
    fun transportClosed() {
        if (released) return
        close(); released = true
        if (active === this) active = null
    }
    companion object {
        // Android's UI dispatcher serializes acquisition/release across Activity instances.
        private var active: VaultTransferOwner? = null
        fun open(session: VaultSession, scope: CoroutineScope, sessionId: String, sessionDeadline: Long,
            now: () -> Long, checkDeadline: () -> Boolean): VaultTransferOwner? {
            if (active != null || scope.coroutineContext[Job]?.isActive == false || !checkDeadline()) return null
            val protocol = TransferProtocol(sessionId, sessionDeadline, now) // Validate before acquiring the draft.
            val token = session.beginTransfer() ?: return null
            return VaultTransferOwner(session, token, session.uiToken(),
                protocol, scope, checkDeadline).also {
                active = it; it.observer.start()
            }
        }
    }
}
