package org.desktop2fa.desktop.watch.vault

/** A small testable boundary; implementations must not log passwords or provider errors. */
internal interface PasswordStore {
    fun read(): String?
    fun write(password: String): Boolean
}

internal sealed interface PasswordCacheRead {
    class Available(val password: String) : PasswordCacheRead {
        override fun toString() = "PasswordCacheRead.Available(redacted)"
    }
    data object Missing : PasswordCacheRead
    data object Unavailable : PasswordCacheRead
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

    fun readResult(): PasswordCacheRead = try {
        val store = openStore()
        if (store == null) PasswordCacheRead.Unavailable
        else {
            val password = store.read()
            when {
                password == null -> PasswordCacheRead.Missing
                password.isBlank() -> PasswordCacheRead.Unavailable
                else -> PasswordCacheRead.Available(password)
            }
        }
    } catch (_: Exception) {
        PasswordCacheRead.Unavailable
    }

    fun read(): String? = (readResult() as? PasswordCacheRead.Available)?.password

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
