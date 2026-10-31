package org.desktop2fa.desktop.watch.vault

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException

class VaultReplacementDeviceTest {
    @Test
    fun androidSupportsAtomicExistingVaultReplacementAndSafeRefusalWithoutIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-replacement-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        val file = File(directory, "synthetic.d2fa")
        try {
            val password = "synthetic device password"
            val oldVault = Vault.create().apply { addEntry("synthetic old", "synthetic service", "MY======") }
            oldVault.saveNew(file, password)
            val updated = Vault.load(file, password).apply { addEntry("synthetic new", "synthetic service", "MZXQ====") }
            updated.save(file, password)
            assertEquals(updated.entries, Vault.load(file, password).entries)
            assertFalse(File(directory, "${file.name}.tmp").exists())
            assertFalse(File(directory, "${file.name}.bak").exists())
            val committed = file.readBytes()
            val unsupported = object : VaultReplacementIO() {
                override fun replace(temp: File, destination: File) {
                    throw AtomicMoveNotSupportedException(temp.path, destination.path, "synthetic unsupported")
                }
            }
            assertThrows(VaultIOException::class.java) { replaceVaultFile(file, committed, unsupported) }
            assertArrayEquals(committed, file.readBytes())
            assertArrayEquals(committed, File(directory, "${file.name}.bak").readBytes())
            assertArrayEquals(committed, File(directory, "${file.name}.tmp").readBytes())
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
