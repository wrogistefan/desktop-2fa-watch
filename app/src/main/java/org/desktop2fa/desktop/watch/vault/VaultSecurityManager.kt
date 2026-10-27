package org.desktop2fa.desktop.watch.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object VaultSecurityManager {
    private const val PREF_NAME = "desktop_2fa_secure_prefs"
    private const val KEY_VAULT_PASSWORD = "vault_password"

    private fun getPreferences(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            try {
                context.getSharedPreferences(PREF_NAME + "_fallback", Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun savePassword(context: Context, password: String) {
        if (password.isBlank()) return
        val prefs = getPreferences(context) ?: return
        try {
            prefs.edit().putString(KEY_VAULT_PASSWORD, password).apply()
        } catch (_: Throwable) {
            // Ignore write failure
        }
    }

    fun getPassword(context: Context): String? {
        val prefs = getPreferences(context) ?: return null
        return try {
            prefs.getString(KEY_VAULT_PASSWORD, null)
        } catch (_: Throwable) {
            null
        }
    }

    fun clearPassword(context: Context) {
        val prefs = getPreferences(context) ?: return
        try {
            prefs.edit().remove(KEY_VAULT_PASSWORD).apply()
        } catch (_: Throwable) {
            // Ignore
        }
    }

    fun hasSavedPassword(context: Context): Boolean {
        return !getPassword(context).isNullOrEmpty()
    }
}
