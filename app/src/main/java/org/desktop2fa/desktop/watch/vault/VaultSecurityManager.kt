package org.desktop2fa.desktop.watch.vault

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
        }
    }

    fun savePassword(context: Context, password: String) {
        if (password.isBlank()) return
        val prefs = getPreferences(context) ?: return
        prefs.edit().putString(KEY_VAULT_PASSWORD, password).apply()
    }

    fun getPassword(context: Context): String? {
        val prefs = getPreferences(context) ?: return null
        return try {
            prefs.getString(KEY_VAULT_PASSWORD, null)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    fun clearPassword(context: Context) {
        val prefs = getPreferences(context) ?: return
        prefs.edit().remove(KEY_VAULT_PASSWORD).apply()
    }

    fun hasSavedPassword(context: Context): Boolean {
        return !getPassword(context).isNullOrEmpty()
    }
}
