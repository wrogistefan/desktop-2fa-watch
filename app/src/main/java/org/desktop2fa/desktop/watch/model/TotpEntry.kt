package org.desktop2fa.desktop.watch.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TotpEntry(
    val issuer: String? = null,
    @SerialName("account_name")
    val accountName: String? = null,
    val secret: String,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1",
)
