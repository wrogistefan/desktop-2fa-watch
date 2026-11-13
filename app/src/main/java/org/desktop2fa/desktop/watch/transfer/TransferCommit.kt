package org.desktop2fa.desktop.watch.transfer

import org.desktop2fa.desktop.watch.model.TransferReceipt
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultFiles
import org.desktop2fa.desktop.watch.vault.VaultPresence
import org.desktop2fa.desktop.watch.vault.VaultSaveOutcome
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal enum class TransferCancellation { CANCELLED, TOO_LATE }

/** One local approval request. Never serialize or reuse it for a second attempt. */
internal class TransferSaveGate {
    private enum class State { APPROVED, SAVING, FINISHED, CANCELLED }
    private val state = AtomicReference(State.APPROVED)
    // Request signal only. The owner sends a terminal wire reply after the
    // storage result; discovering an older durable receipt takes precedence.
    fun cancel(): TransferCancellation {
        while (true) when (val current = state.get()) {
            State.CANCELLED -> return TransferCancellation.CANCELLED
            State.SAVING, State.FINISHED -> return TransferCancellation.TOO_LATE
            State.APPROVED -> if (state.compareAndSet(current, State.CANCELLED)) return TransferCancellation.CANCELLED
        }
    }
    internal fun available(): Boolean = state.get() == State.APPROVED
    internal fun cancelled(): Boolean = state.get() == State.CANCELLED
    internal fun begin(): Boolean = state.compareAndSet(State.APPROVED, State.SAVING)
    internal fun finish() { state.compareAndSet(State.SAVING, State.FINISHED) }
    internal fun observedSaved() { state.set(State.FINISHED) }
    override fun toString() = "TransferSaveGate(redacted)"
}

internal sealed interface TransferCommitResult {
    class Saved(receipt: TransferReceipt, val vault: Vault?, val requiresStorageReview: Boolean) : TransferCommitResult {
        private val owned = receipt.snapshot()
        val receipt get() = owned.snapshot()
        override fun toString() = "Saved(redacted)"
    }
    data object Stale : TransferCommitResult
    data object Cancelled : TransferCommitResult
    class Unconfirmed(val outcome: VaultSaveOutcome) : TransferCommitResult {
        override fun toString() = "Unconfirmed(redacted)"
    }
}

/** Same atomic Vault.save path as manual additions. Fault seams never alter encryption. */
internal open class TransferCommitIO {
    open fun load(file: File, password: String): Vault = Vault.load(file, password)
    open fun save(vault: Vault, file: File, password: String): VaultSaveOutcome = vault.save(file, password)
    open fun inspect(file: File): VaultPresence = VaultFiles.inspect(file)
    open fun mainPresent(file: File): Boolean = VaultFiles.inventory(file).let { inventory ->
        inventory.candidates.any { it.file == inventory.main }
    }
}

/**
 * Caller holds the existing process-wide session writer mutex and owns a
 * non-cancellable mutation. Approval is local and bound to this exact candidate.
 * No network/Compose state, password persistence, migration or automatic retry.
 */
internal fun commitApprovedTransfer(
    candidate: TransferAdditionCandidate,
    file: File,
    password: String,
    approval: TransferSaveGate,
    io: TransferCommitIO = TransferCommitIO(),
): TransferCommitResult {
    // Before the publication boundary, cancellation/Lock may still win.
    if (!approval.available()) return if (approval.cancelled()) TransferCommitResult.Cancelled
        else TransferCommitResult.Unconfirmed(VaultSaveOutcome.UNCERTAIN)
    val expectedReceipt = candidate.receipt
    val current: Vault
    val prior: TransferReceipt?
    try {
        current = io.load(file, password)
        prior = TransferPlanner.status(current.data, expectedReceipt.transferId, expectedReceipt.offerSha256)
    } catch (failure: Exception) { approval.cancel(); throw failure }
    if (prior != null) {
        // Read-only replay: an actual retained receipt is evidence, not another save.
        approval.observedSaved() // An existing commit cannot be rolled back by cancelling a replay.
        val state = readableMainState(file, io)
            ?: return TransferCommitResult.Unconfirmed(VaultSaveOutcome.COMMITTED)
        return TransferCommitResult.Saved(prior, current.takeIf { state }, !state)
    }
    if (approval.cancelled()) return TransferCommitResult.Cancelled
    if (!candidate.matches(current.data)) { approval.cancel(); return TransferCommitResult.Stale }
    val cleanBefore = try { io.inspect(file) == VaultPresence.PRESENT }
        catch (failure: Exception) { approval.cancel(); throw failure }
    if (!cleanBefore) {
        approval.cancel()
        return TransferCommitResult.Unconfirmed(VaultSaveOutcome.NOT_COMMITTED)
    }
    // Keep verification expectations private from a faulty writer mutating its argument.
    val expectedData = candidate.vault().data
    val originalData = current.data.copy(entries = current.data.entries.toMutableList(),
        transferReceipts = current.data.transferReceipts.map { it.snapshot() })
    val proposed = candidate.vault()
    if (!approval.begin()) return if (approval.cancelled()) TransferCommitResult.Cancelled
        else TransferCommitResult.Unconfirmed(VaultSaveOutcome.UNCERTAIN)
    // This CAS is the approval/save boundary. CANCEL now returns TOO_LATE.
    try {
        val reported = try { io.save(proposed, file, password) }
            catch (_: Exception) { VaultSaveOutcome.UNCERTAIN }
        // Even NOT_COMMITTED/exception is reconciled by actual authenticated data.
        val actual = try { io.load(file, password) } catch (_: Exception) { null }
        val receipt = actual?.let {
            try { TransferPlanner.status(it.data, expectedReceipt.transferId, expectedReceipt.offerSha256) }
            catch (_: TransferValidationFailure) { null }
        }
        if (actual?.data == expectedData && receipt == expectedReceipt) {
            val state = readableMainState(file, io)
            if (state != null) {
                val clean = state && reported != VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS
                return TransferCommitResult.Saved(receipt, actual.takeIf { clean }, !clean)
            }
        }
        // Never return a receipt from the proposed in-memory object as RESULT.
        val knownCommit = reported == VaultSaveOutcome.COMMITTED || reported == VaultSaveOutcome.COMMITTED_WITH_RECOVERY_ARTIFACTS
        val unchangedFailure = reported == VaultSaveOutcome.NOT_COMMITTED && actual?.data == originalData
        return TransferCommitResult.Unconfirmed(when {
            knownCommit -> reported
            unchangedFailure -> VaultSaveOutcome.NOT_COMMITTED
            else -> VaultSaveOutcome.UNCERTAIN
        })
    } finally { approval.finish() }
}

/** Null is missing/unknown main, false is confirmed main with recovery artifacts. */
private fun readableMainState(file: File, io: TransferCommitIO): Boolean? = try {
    when (io.inspect(file)) {
        VaultPresence.PRESENT -> true
        VaultPresence.MISSING -> null
        VaultPresence.RECOVERY_REQUIRED -> if (io.mainPresent(file)) false else null
    }
} catch (_: Exception) { null }
