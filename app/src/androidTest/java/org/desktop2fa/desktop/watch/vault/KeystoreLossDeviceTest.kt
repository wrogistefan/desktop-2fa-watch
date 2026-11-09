package org.desktop2fa.desktop.watch.vault

import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import org.xmlpull.v1.XmlPullParser

class KeystoreLossDeviceTest {
    @Test
    fun packagedBackupPoliciesExcludeEveryKnownCredentialStore() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val expected = PasswordCache.STORE_NAMES.map { "$it.xml" }.toSet()
        val groups = mutableMapOf<String, MutableSet<String>>()
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            resources.getXml(resource).use { parser ->
                var group: String? = null
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        if (parser.name in setOf("full-backup-content", "cloud-backup", "device-transfer")) {
                            group = parser.name
                            groups[group] = mutableSetOf()
                        } else if (parser.name == "exclude" && group != null && parser.getAttributeValue(null, "domain") == "sharedpref") {
                            groups.getValue(group).add(parser.getAttributeValue(null, "path"))
                        }
                    } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == group) group = null
                    parser.next()
                }
            }
        }
        assertEquals(setOf("full-backup-content", "cloud-backup", "device-transfer"), groups.keys)
        groups.forEach { (group, excluded) -> assertTrue("All known stores excluded in $group", excluded.containsAll(expected)) }
    }

    @Test
    fun deletingOnlyAnOwnedSyntheticKeystoreKeyPreservesVaultAndAllowsManualUnlock() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        val context = SyntheticWatchContext(app, directory)
        val alias = "synthetic_watch_cache_${UUID.randomUUID()}"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cache = VaultSecurityManager.cache(context, alias)
        val file = File(directory, "vault.d2fa")
        val password = "synthetic key loss password"
        try {
            Vault.create().apply { addEntry("synthetic account", "synthetic service", "MY======") }.saveNew(file, password)
            val original = file.readBytes()
            assertTrue(cache.save(password))
            assertEquals(password, cache.read())
            assertTrue(cache.readResult() is PasswordCacheRead.Available)
            assertTrue(keyStore.containsAlias(alias))
            // Never touch DEFAULT_MASTER_KEY_ALIAS or any alias not created by this test.
            check(alias.startsWith("synthetic_watch_cache_"))
            keyStore.deleteEntry(alias)
            assertNull(cache.read())
            assertSame(PasswordCacheRead.Unavailable, cache.readResult())
            assertFalse(cache.save("synthetic replacement password"))
            assertArrayEquals(original, file.readBytes())
            assertEquals(1, Vault.load(file, password).entries.size)
            assertArrayEquals(original, file.readBytes())
            assertTrue(cache.clear())
            assertSame(PasswordCacheRead.Missing, cache.readResult())
            assertTrue(cache.save(password))
            assertEquals(password, cache.read())
            assertArrayEquals(original, file.readBytes())
        } finally {
            cache.clear()
            check(alias.startsWith("synthetic_watch_cache_"))
            keyStore.deleteEntry(alias)
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }
}
