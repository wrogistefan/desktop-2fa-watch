package org.desktop2fa.desktop.watch.vault

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

object VaultSecurityManager {
    private const val KEY_VAULT_PASSWORD = "vault_password"

    private fun cache(context: Context) = PasswordCache(
        openStore = {
            createEncryptedPreferences(context)?.let { prefs ->
                object : PasswordStore {
                    override fun read(): String? = prefs.getString(KEY_VAULT_PASSWORD, null)

                    @SuppressLint("ApplySharedPref")
                    override fun write(password: String): Boolean =
                        prefs.edit().putString(KEY_VAULT_PASSWORD, password).commit()
                }
            }
        },
        deleteStore = { name -> context.deleteSharedPreferences(name) },
    )

    private fun createEncryptedPreferences(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PasswordCache.SECURE_STORE,
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
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun savePassword(context: Context, password: String): Boolean {
        return cache(context).save(password)
    }

    @Synchronized
    fun getPassword(context: Context): String? {
        return cache(context).read()
    }

    @Synchronized
    fun clearPassword(context: Context): Boolean {
        return cache(context).clear()
    }

    fun hasSavedPassword(context: Context): Boolean {
        return !getPassword(context).isNullOrEmpty()
    }
}
