package org.desktop2fa.desktop.watch.vault

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.syntheticRecoveryDirectory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultRecoveryPathDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realAndroidSymlinksAreRefusedAtLoadAndAfterRecoveryInspection() {
        val directory = File(context.cacheDir, "synthetic-paths-${System.nanoTime()}").apply { check(mkdir()) }
        val original = File(directory, "original.fixture")
        val password = "synthetic path password"
        try {
            Vault.create().saveNew(original, password)
            val bytes = original.readBytes()
            val link = File(directory, "linked.d2fa")
            Os.symlink(original.path, link.path)
            assertThrows(VaultIOException::class.java) { Vault.load(link, password) }
            val main = File(directory, "vault.d2fa")
            val backup = original.copyTo(File(directory, "vault.d2fa.bak"))
            val selected = VaultFiles.inventory(main).candidates.single()
            assertThrows(VaultIOException::class.java) {
                openRecoveryView(main, selected, password) { file, supplied ->
                    // Replace only our synthetic file after inventory validation.
                    Files.delete(file.toPath())
                    Os.symlink(original.path, file.path)
                    Vault.load(file, supplied)
                }
            }
            assertArrayEquals(bytes, original.readBytes())
            assertTrue(Files.isSymbolicLink(backup.toPath()))
            assertFalse(main.exists())
        } finally {
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }

    @Test
    fun debugDirectoryValidationRefusesSymlinksAndTraversalWithoutTouchingTheTarget() {
        val cache = context.cacheDir
        val target = File(cache, "synthetic-outside-${System.nanoTime()}").apply { check(mkdir()) }
        val valid = File(cache, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        val link = File(cache, "synthetic-restart-${System.nanoTime()}")
        try {
            Os.symlink(target.path, link.path)
            assertEquals(valid.canonicalFile, syntheticRecoveryDirectory(cache, valid.name))
            assertNull(syntheticRecoveryDirectory(cache, link.name))
            assertNull(syntheticRecoveryDirectory(cache, "../${target.name}"))
            assertEquals(emptyList<String>(), target.list()!!.toList())
        } finally {
            Files.deleteIfExists(link.toPath())
            Files.delete(valid.toPath())
            Files.delete(target.toPath())
        }
    }
}
