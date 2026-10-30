package org.desktop2fa.desktop.watch.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required

@Serializable
data class VaultData(
    @Required
    @Serializable(with = PayloadVersionSerializer::class)
    val version: Int = 1,
    val entries: MutableList<TotpEntry> = mutableListOf(),
)
