package org.desktop2fa.desktop.watch.transfer

import org.bouncycastle.util.encoders.Base32
import org.desktop2fa.desktop.watch.model.*
import org.desktop2fa.desktop.watch.vault.Vault

/** Pure preparation only. Integration must recheck under the existing session write mutex. */
internal sealed interface TransferPreparation {
    class AlreadySaved(receipt: TransferReceipt) : TransferPreparation {
        private val owned = receipt.snapshot()
        val receipt get() = owned.snapshot()
        override fun toString() = "AlreadySaved(redacted)"
    }
    class Review(val candidate: TransferAdditionCandidate) : TransferPreparation {
        override fun toString() = "Review(redacted)"
    }
}

internal class TransferAdditionCandidate internal constructor(
    baseline: VaultData, additions: List<TotpEntry>, receipt: TransferReceipt,
) {
    private val entries = baseline.entries.toList()
    private val receipts = baseline.transferReceipts.map { it.snapshot() }
    private val added = additions.toList()
    private val savedReceipt = receipt.snapshot()
    val receipt get() = savedReceipt.snapshot()
    fun matches(current: VaultData) = current.version == 2 && current.entries == entries &&
        current.transferReceipts == receipts
    fun vault(): Vault = Vault(VaultData(2, (entries + added).toMutableList(),
        (receipts + savedReceipt).takeLast(VaultPayloadValidation.MAX_RECEIPTS).map { it.snapshot() }))
    override fun toString() = "TransferAdditionCandidate(redacted)"
}

internal object TransferPlanner {
    /** Unknown is absence from retained receipts, never proof of no historical save. */
    fun status(snapshot: VaultData, transferId: String, sha256: String): TransferReceipt? {
        try {
            require(snapshot.version == 2) { "Transfer requires explicitly migrated vault v2" }
            VaultPayloadValidation.requireValid(snapshot)
        } catch (_: IllegalArgumentException) {
            // A valid v1 vault is not transfer-ready; never migrate or change its schema here.
            throw TransferValidationFailure(TransferError.STORAGE_ERROR)
        }
        if (!Regex("[0-9a-f]{32}").matches(transferId) || !Regex("[0-9a-f]{64}").matches(sha256))
            throw TransferValidationFailure(TransferError.INVALID_MESSAGE)
        val receipt = snapshot.transferReceipts.singleOrNull { it.transferId == transferId } ?: return null
        if (receipt.offerSha256 != sha256) throw TransferValidationFailure(TransferError.TX_CONFLICT)
        return receipt.snapshot()
    }

    fun prepare(snapshot: VaultData, offer: TransferOffer): TransferPreparation {
        val baseline = VaultData(snapshot.version, snapshot.entries.toMutableList(),
            snapshot.transferReceipts.map { it.snapshot() })
        status(baseline, offer.transferId, offer.sha256)?.let { return TransferPreparation.AlreadySaved(it) }
        val incoming = offer.accounts
        val decoded = ArrayList<ByteArray>()
        try {
            val newSecrets = incoming.map { decode(it.entry.secret).also(decoded::add) }
            val oldSecrets = baseline.entries.map { decode(it.secret).also(decoded::add) }
            val repeated = HashSet<String>()
            for (i in incoming.indices) for (j in 0 until i) {
                if (newSecrets[i].contentEquals(newSecrets[j]) || sameName(incoming[i].entry, incoming[j].entry)) {
                    repeated += incoming[i].itemId; repeated += incoming[j].itemId
                }
            }
            if (repeated.isNotEmpty()) throw TransferValidationFailure(TransferError.DUPLICATE_IN_BATCH,
                incoming.map { it.itemId }.filter(repeated::contains))
            val parameterConflicts = ArrayList<String>()
            val nameConflicts = ArrayList<String>()
            val additions = ArrayList<TotpEntry>()
            val addedIds = ArrayList<String>()
            val skippedIds = ArrayList<String>()
            for (i in incoming.indices) {
                val item = incoming[i]
                var duplicate = false
                for (j in baseline.entries.indices) {
                    val previous = baseline.entries[j]
                    if (newSecrets[i].contentEquals(oldSecrets[j])) {
                        if (!sameParameters(item.entry, previous)) parameterConflicts += item.itemId
                        else duplicate = true
                    } else if (sameName(item.entry, previous)) nameConflicts += item.itemId
                }
                if (duplicate) skippedIds += item.itemId
                else { additions += item.entry; addedIds += item.itemId }
            }
            // Inspect every historical match: a conflict overrides any otherwise valid skip.
            if (parameterConflicts.isNotEmpty()) throw TransferValidationFailure(TransferError.PARAMETER_CONFLICT,
                parameterConflicts.distinct())
            if (nameConflicts.isNotEmpty()) throw TransferValidationFailure(TransferError.NAME_CONFLICT, nameConflicts.distinct())
            return TransferPreparation.Review(TransferAdditionCandidate(baseline, additions,
                TransferReceipt(offer.transferId, offer.sha256, addedIds, skippedIds)))
        } finally { decoded.forEach { it.fill(0) } }
    }
    private fun decode(secret: String) = Base32.decode(SetupKey.canonical(secret))
    private fun sameName(a: TotpEntry, b: TotpEntry) = a.issuer == b.issuer && a.accountName == b.accountName
    private fun sameParameters(a: TotpEntry, b: TotpEntry) = a.algorithm == b.algorithm && a.digits == b.digits && a.period == b.period
}
