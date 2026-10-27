package org.desktop2fa.desktop.watch.vault

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

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
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    @SuppressLint("ApplySharedPref")
    fun savePassword(context: Context, password: String): Boolean {
        if (password.isBlank()) return false
        val prefs = getPreferences(context) ?: return false
        return try {
            prefs.edit().putString(KEY_VAULT_PASSWORD, password).commit()
        } catch (_: Throwable) {
            false
        }
    }

    fun getPassword(context: Context): String? {
        val prefs = getPreferences(context) ?: return null
        return try {
            prefs.getString(KEY_VAULT_PASSWORD, null)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    @SuppressLint("ApplySharedPref")
    fun clearPassword(context: Context): Boolean {
        val prefs = getPreferences(context) ?: return true
        return try {
            prefs.edit().remove(KEY_VAULT_PASSWORD).commit()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasSavedPassword(context: Context): Boolean {
        return !getPassword(context).isNullOrEmpty()
    }
}
