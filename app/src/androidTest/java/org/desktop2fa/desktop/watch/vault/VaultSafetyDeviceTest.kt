package org.desktop2fa.desktop.watch.vault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class VaultSafetyDeviceTest {
    // Isolate every preference file. Never clear a user's cache or delete a Keystore key.
    private fun testContext(): Context = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        private val prefix = "synthetic_regression_"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)
        override fun deleteSharedPreferences(name: String): Boolean =
            super.deleteSharedPreferences(prefix + name)
    }

    @Test
    fun realEncryptedCacheCanBeSavedReadAndCleared() {
        val context = testContext()
        try {
            assertTrue(VaultSecurityManager.clearPassword(context))
            assertTrue(VaultSecurityManager.savePassword(context, "synthetic device password"))
            assertEquals("synthetic device password", VaultSecurityManager.getPassword(context))
            assertTrue(VaultSecurityManager.clearPassword(context))
            assertNull(VaultSecurityManager.getPassword(context))
            assertTrue(VaultSecurityManager.clearPassword(context))
        } finally {
            VaultSecurityManager.clearPassword(context)
        }
    }

    @Test
    fun unreadableEncryptedPreferencesAndLegacyStoresCanBeCleared() {
        val context = testContext()
        try {
            assertTrue(VaultSecurityManager.clearPassword(context))
            // Corrupt only our synthetic preference keyset, not the real Keystore alias.
            val prefs = context.getSharedPreferences(PasswordCache.SECURE_STORE, Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "invalid-synthetic-keyset").commit())
            for (name in PasswordCache.STORE_NAMES.drop(1)) {
                assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    .putString("vault_password", "synthetic legacy password").commit())
            }
            assertNull(VaultSecurityManager.getPassword(context))
            assertTrue(VaultSecurityManager.clearPassword(context))
            for (name in PasswordCache.STORE_NAMES) {
                assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
            }
        } finally {
            VaultSecurityManager.clearPassword(context)
        }
    }

    @Test
    fun createOnlyWriteOnAndroidDoesNotReplaceACompetingFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-vault-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        val destination = File(directory, "synthetic.d2fa")
        try {
            assertEquals(VaultPresence.MISSING, VaultFiles.inspect(destination))
            VaultFiles.writeNew(destination, "synthetic complete candidate".toByteArray())
            assertEquals(VaultPresence.PRESENT, VaultFiles.inspect(destination))
            assertThrows(IOException::class.java) {
                VaultFiles.writeNew(destination, "synthetic other candidate".toByteArray())
            }
            assertEquals("synthetic complete candidate", destination.readText())
        } finally {
            destination.delete()
            directory.delete()
        }
    }
}
