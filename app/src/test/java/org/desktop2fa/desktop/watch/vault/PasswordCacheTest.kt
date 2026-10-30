package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PasswordCacheTest {
    @Test
    fun unavailableOrFailingStorageDoesNotReportSavedPassword() {
        assertFalse(PasswordCache({ null }, { true }).save("synthetic password"))
        val cache = PasswordCache({ throw IOException("synthetic failure") }, { true })
        assertFalse(cache.save("synthetic password"))
        assertNull(cache.read())
    }

    @Test
    fun commitFailureAndReadFailureAreHandled() {
        val cache = PasswordCache({ object : PasswordStore {
            override fun write(password: String) = false
            override fun read(): String? = throw IllegalStateException("synthetic invalid key")
        } }, { true })
        assertFalse(cache.save("synthetic password"))
        assertNull(cache.read())
    }

    @Test
    fun clearingDoesNotNeedAReadableKeystoreAndDeletesEveryBackingStore() {
        val attempted = mutableListOf<String>()
        val cache = PasswordCache({ error("Must not decrypt during cleanup") }, {
            attempted += it
            true
        })
        assertTrue(cache.clear())
        assertEquals(PasswordCache.STORE_NAMES, attempted)
    }

    @Test
    fun unsuccessfulDeletionIsNotSuccessAndDoesNotSkipOtherStores() {
        for (failingStore in PasswordCache.STORE_NAMES) {
            for (throws in listOf(false, true)) {
                val attempted = mutableListOf<String>()
                val cache = PasswordCache({ null }, {
                    attempted += it
                    if (it == failingStore && throws) throw IOException("synthetic failure")
                    it != failingStore
                })
                assertFalse(cache.clear())
                assertEquals(PasswordCache.STORE_NAMES, attempted)
            }
        }
    }

    @Test
    fun successfulRoundTripAndEmptyPasswordRejection() {
        var stored: String? = null
        val cache = PasswordCache({ object : PasswordStore {
            override fun read() = stored
            override fun write(password: String): Boolean { stored = password; return true }
        } }, { stored = null; true })
        assertFalse(cache.save(" "))
        assertTrue(cache.save("synthetic password"))
        assertEquals("synthetic password", cache.read())
        assertTrue(cache.clear())
        assertNull(cache.read())
    }
}
