package org.desktop2fa.desktop.watch.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

object VaultSecurityManager {
    private const val PREF_NAME = "desktop_2fa_secure_prefs"
    private const val FALLBACK_PREF_NAME = "desktop_2fa_fallback_prefs"
    private const val KEY_VAULT_PASSWORD = "vault_password"

    private fun getEncryptedPreferences(context: Context): SharedPreferences? {
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
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun getFallbackPreferences(context: Context): SharedPreferences? {
        return try {
            context.getSharedPreferences(FALLBACK_PREF_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    fun savePassword(context: Context, password: String) {
        if (password.isBlank()) return
        val encryptedPrefs = getEncryptedPreferences(context)
        if (encryptedPrefs != null) {
            try {
                encryptedPrefs.edit().putString(KEY_VAULT_PASSWORD, password).apply()
                // Clear fallback if encrypted store works
                getFallbackPreferences(context)?.edit()?.remove(KEY_VAULT_PASSWORD)?.apply()
                return
            } catch (_: Throwable) {
                // If encrypted store write fails, fallback below
            }
        }

        // Fallback store
        try {
            getFallbackPreferences(context)?.edit()?.putString(KEY_VAULT_PASSWORD, password)?.apply()
        } catch (_: Throwable) {
            // Ignore
        }
    }

    fun getPassword(context: Context): String? {
        val encryptedPrefs = getEncryptedPreferences(context)
        if (encryptedPrefs != null) {
            val pass = try {
                encryptedPrefs.getString(KEY_VAULT_PASSWORD, null)
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: ClassCastException) {
                null
            }
            if (!pass.isNullOrEmpty()) return pass
        }

        // Fallback store
        val fallbackPrefs = getFallbackPreferences(context) ?: return null
        return try {
            fallbackPrefs.getString(KEY_VAULT_PASSWORD, null)
        } catch (_: Throwable) {
            null
        }
    }

    fun clearPassword(context: Context) {
        // Clear from encrypted store
        try {
            getEncryptedPreferences(context)?.edit()?.remove(KEY_VAULT_PASSWORD)?.apply()
        } catch (_: Throwable) {
            // Ignore
        }

        // Clear from fallback store
        try {
            getFallbackPreferences(context)?.edit()?.remove(KEY_VAULT_PASSWORD)?.apply()
        } catch (_: Throwable) {
            // Ignore
        }
    }

    fun hasSavedPassword(context: Context): Boolean {
        return !getPassword(context).isNullOrEmpty()
    }
}
