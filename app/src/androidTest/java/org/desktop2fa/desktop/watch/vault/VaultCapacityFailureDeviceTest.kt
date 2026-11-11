package org.desktop2fa.desktop.watch.vault

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Bounded capacity-fault model on Android, not exhaustion of the Watch volume. */
class VaultCapacityFailureDeviceTest {
    @Test fun partialTemporaryWriteFailurePreservesTheOriginalEncryptedVault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "synthetic-capacity-${System.nanoTime()}")
        check(directory.mkdir())
        val file = File(directory, "vault.d2fa")
        try {
            val original = instrumentation.context.assets.open("kdf/supported.d2fa").use { it.readBytes() }
            check(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) } ==
                "a819a0f6db135b622ef0d09c78b7eee6affe7c569a31f4f1c5f52205327e4291")
            file.writeBytes(original)
            val password = "Synthetic-Żółw-🔐-42!"
            val candidate = Vault.load(file, password)
            val originalEntries = candidate.entries.toList()
            candidate.addEntry("synthetic capacity candidate", "synthetic", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            val limited = object : VaultReplacementIO() {
                override fun openTemp(file: File): VaultOutput {
                    val real = super.openTemp(file)
                    return object : VaultOutput {
                        override fun write(bytes: ByteArray) {
                            real.write(bytes.copyOfRange(0, minOf(64, bytes.size)))
                            real.flush()
                            real.sync()
                            throw IOException("synthetic capacity exhausted after partial write")
                        }
                        override fun flush() = real.flush()
                        override fun sync() = real.sync()
                        override fun close() = real.close()
                    }
                }
            }
            assertEquals(VaultSaveOutcome.NOT_COMMITTED, candidate.save(file, password, limited))
            assertArrayEquals(original, file.readBytes())
            assertEquals(64L, File(directory, "vault.d2fa.tmp").length())
            assertFalse(File(directory, "vault.d2fa.bak").exists())
            assertTrue("Original synthetic records reload unchanged", originalEntries == Vault.load(file, password).entries)
            assertArrayEquals(original, file.readBytes())
        } finally {
            // All work above is synchronous; no writer remains active here.
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }
}
