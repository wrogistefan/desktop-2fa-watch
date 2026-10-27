package org.desktop2fa.desktop.watch.model

import kotlinx.serialization.Serializable

@Serializable
data class VaultData(
    val version: Int = 1,
    val entries: MutableList<TotpEntry> = mutableListOf(),
)
