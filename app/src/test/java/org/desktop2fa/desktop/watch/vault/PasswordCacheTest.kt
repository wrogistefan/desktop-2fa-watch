package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PasswordCacheTest {
    @Test
    fun missingUnavailableAndAvailableReadsAreDistinctWithoutWritingOrDeleting() {
        var writes = 0
        var deletes = 0
        fun cache(open: () -> PasswordStore?) = PasswordCache(open, { deletes++; true })
        fun store(value: String?) = object : PasswordStore {
            override fun read() = value
            override fun write(password: String): Boolean { writes++; return true }
        }
        assertSame(PasswordCacheRead.Missing, cache { store(null) }.readResult())
        assertSame(PasswordCacheRead.Unavailable, cache { null }.readResult())
        assertSame(PasswordCacheRead.Unavailable, cache { throw IOException("synthetic") }.readResult())
        assertSame(PasswordCacheRead.Unavailable, cache { store(" ") }.readResult())
        val available = cache { store("synthetic password") }.readResult() as PasswordCacheRead.Available
        assertEquals("synthetic password", available.password)
        assertFalse(available.toString().contains(available.password))
        assertEquals(0, writes)
        assertEquals(0, deletes)
    }

    @Test
    fun aReadExceptionIsUnavailableAndCannotTriggerCleanupOrAnEmptyReplacement() {
        val cache = PasswordCache({ object : PasswordStore {
            override fun read(): String? = throw IOException("synthetic key failure")
            override fun write(password: String): Boolean = error("No writes on a failed read")
        } }, { error("No deletion on a failed read") })
        assertSame(PasswordCacheRead.Unavailable, cache.readResult())
        assertNull(cache.read())
    }

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
        assertEquals(listOf(
            "desktop_2fa_secure_prefs",
            "desktop_2fa_fallback_prefs",
            "desktop_2fa_secure_prefs_fallback",
        ), attempted)
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
