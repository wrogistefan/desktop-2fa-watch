package org.desktop2fa.desktop.watch.model

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Encrypted transaction metadata only. Never use this model as a diagnostic string. */
@Serializable
data class TransferReceipt(
    @SerialName("transfer_id") val transferId: String,
    @SerialName("offer_sha256") val offerSha256: String,
    @SerialName("added_item_ids") val addedItemIds: List<String>,
    @SerialName("skipped_item_ids") val skippedItemIds: List<String>,
) {
    fun snapshot(): TransferReceipt = copy(
        addedItemIds = addedItemIds.toList(), skippedItemIds = skippedItemIds.toList(),
    )

    // Do not let incidental diagnostics expose secret-derived digests or item metadata.
    override fun toString(): String = "TransferReceipt(redacted)"
}

/** Separate serializer prevents accidentally extending the strict v1 payload schema. */
@Serializable
internal data class VaultPayloadV2(
    @Required @Serializable(with = PayloadVersionSerializer::class) val version: Int = 2,
    val entries: List<TotpEntry>,
    @SerialName("transfer_receipts") val transferReceipts: List<TransferReceipt>,
)

internal object VaultPayloadValidation {
    const val MAX_RECEIPTS = 128
    private val id = Regex("[0-9a-f]{32}")
    private val digest = Regex("[0-9a-f]{64}")

    fun requireValid(data: VaultData) {
        require(data.version == 1 || data.version == 2) { "Unsupported vault data version" }
        require(data.version == 2 || data.transferReceipts.isEmpty()) { "Receipts require vault v2" }
        data.entries.forEach(AccountValidation::requireValid)
        require(data.transferReceipts.size <= MAX_RECEIPTS) { "Invalid transfer receipts" }
        val transferIds = HashSet<String>()
        for (receipt in data.transferReceipts) {
            require(id.matches(receipt.transferId) && transferIds.add(receipt.transferId) &&
                digest.matches(receipt.offerSha256)) { "Invalid transfer receipt" }
            val ids = receipt.addedItemIds + receipt.skippedItemIds
            require(ids.size in 1..50 && ids.all(id::matches) && ids.toSet().size == ids.size) {
                "Invalid transfer receipt items"
            }
        }
    }
}
