package org.desktop2fa.desktop.watch.vault

import android.app.ActivityManager
import android.content.Intent
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticRecoveryProcessActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultRecoveryRestartDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runChild(directory: File, stage: String): Pair<Int, String> {
        val result = File(directory, "result.txt")
        Files.deleteIfExists(result.toPath())
        context.startActivity(Intent(context, SyntheticRecoveryProcessActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("syntheticDirectory", directory.name).putExtra("stage", stage))
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!result.exists() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue("Synthetic child completed $stage", result.exists())
        val fields = result.readText().split('|')
        val pid = fields[0].toInt()
        assertNotEquals(Process.myPid(), pid)
        val manager = context.getSystemService(ActivityManager::class.java)
        val shutdownDeadline = System.nanoTime() + 30_000_000_000L
        while (manager.runningAppProcesses.orEmpty().any { it.pid == pid } && System.nanoTime() < shutdownDeadline) Thread.sleep(100)
        assertFalse("Synthetic child terminated", manager.runningAppProcesses.orEmpty().any { it.pid == pid })
        return pid to fields[1]
    }

    private fun cleanSyntheticDirectory(directory: File) {
        Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
        Files.delete(directory.toPath())
    }

    @Test
    fun missingSyntheticFixtureReportsOnlyAConstantErrorAndTerminates() {
        val directory = File(context.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            assertEquals("ERROR", runChild(directory, "beforeTemp").second)
            assertFalse(File(directory, "synthetic.d2fa").exists())
        } finally {
            cleanSyntheticDirectory(directory)
        }
    }

    @Test
    fun killedWriterAndRelaunchedInspectorPreserveAndAuthenticateRecoveryCandidates() {
        val prepared = File(context.cacheDir, "synthetic-prepared-${System.nanoTime()}").apply { check(mkdir()) }
        val password = "synthetic restart password"
        val oldFile = File(prepared, "old.fixture")
        val newFile = File(prepared, "new.fixture")
        val oldVault = Vault.create().apply { addEntry("synthetic old", "synthetic service", "MY======") }
        val newVault = Vault.create().apply { addEntry("synthetic new", "synthetic service", "MZXQ====") }
        try {
            oldVault.saveNew(oldFile, password)
            newVault.saveNew(newFile, password)
            for (stage in listOf("beforeTemp", "afterTemp", "afterBackup", "afterCommit", "partialRestore")) {
                val directory = File(context.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
                try {
                    oldFile.copyTo(File(directory, "old.fixture"))
                    newFile.copyTo(File(directory, "new.fixture"))
                    val writer = runChild(directory, stage)
                    assertEquals(stage, writer.second)
                    val inspector = runChild(directory, "inspect")
                    assertNotEquals(writer.first, inspector.first)
                    val expected = if (stage == "beforeTemp") VaultPresence.PRESENT else VaultPresence.RECOVERY_REQUIRED
                    assertEquals(expected.name, inspector.second)
                    val main = File(directory, "synthetic.d2fa")
                    val candidates = VaultFiles.inventory(main).candidates
                    val before = candidates.associate { it.file to it.file.readBytes() }
                    val selected = if (stage == "partialRestore") candidates.single { it.label == "Previous backup" }
                        else candidates.single { it.label == "Current vault" }
                    val view = openRecoveryView(main, selected, password)
                    assertEquals(if (stage == "afterCommit") newVault.entries else oldVault.entries, view.vault.entries)
                    assertThrows(InvalidPasswordException::class.java) { openRecoveryView(main, selected, "synthetic wrong password") }
                    before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
                    if (stage == "partialRestore") {
                        assertThrows(UnsupportedFormatException::class.java) {
                            openRecoveryView(main, candidates.single { it.label == "Current vault" }, password)
                        }
                    }
                } finally {
                    cleanSyntheticDirectory(directory)
                }
            }
        } finally {
            cleanSyntheticDirectory(prepared)
        }
    }
}
