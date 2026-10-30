package org.desktop2fa.desktop.watch.vault

/** A small testable boundary; implementations must not log passwords or provider errors. */
internal interface PasswordStore {
    fun read(): String?
    fun write(password: String): Boolean
}

internal class PasswordCache(
    private val openStore: () -> PasswordStore?,
    private val deleteStore: (String) -> Boolean,
) {
    fun save(password: String): Boolean {
        if (password.isBlank()) return false
        return try {
            openStore()?.write(password) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun read(): String? = try {
        openStore()?.read()
    } catch (_: Exception) {
        null
    }

    fun clear(): Boolean {
        var success = true
        // Delete without decrypting: a lost Keystore key must not prevent cleanup.
        for (name in STORE_NAMES) {
            val deleted = try {
                deleteStore(name)
            } catch (_: Exception) {
                false
            }
            if (!deleted) success = false
        }
        return success
    }

    companion object {
        const val SECURE_STORE = "desktop_2fa_secure_prefs"
        val STORE_NAMES = listOf(SECURE_STORE, "desktop_2fa_fallback_prefs", "desktop_2fa_secure_prefs_fallback")
    }
}
