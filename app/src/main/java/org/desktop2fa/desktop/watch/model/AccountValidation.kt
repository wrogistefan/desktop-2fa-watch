package org.desktop2fa.desktop.watch.model

/** Shared by vault boundaries and future importers; name collisions are a separate policy. */
object AccountValidation {
    fun requireValid(entry: TotpEntry) {
        require(SetupKey.error(entry.secret) == null) { "Invalid account setup key" }
        require(entry.algorithm in setOf("SHA1", "SHA256", "SHA512")) { "Unsupported TOTP algorithm" }
        require(entry.digits in 6..8) { "TOTP digits must be 6, 7 or 8" }
        require(entry.period > 0) { "TOTP period must be positive" }
    }
}
