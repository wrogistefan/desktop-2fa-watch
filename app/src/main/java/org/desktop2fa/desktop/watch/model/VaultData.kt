package org.desktop2fa.desktop.watch.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.Transient

@Serializable
data class VaultData(
    @Required
    @Serializable(with = PayloadVersionSerializer::class)
    val version: Int = 1,
    val entries: MutableList<TotpEntry> = mutableListOf(),
    // v1 serializer must never accept or emit v2 fields. VaultPayloadV2 owns v2 encoding.
    @Transient val transferReceipts: List<TransferReceipt> = emptyList(),
)
