package org.desktop2fa.desktop.watch.presentation

import android.app.ActivityManager
import android.content.Intent
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.desktop2fa.desktop.watch.testing.SyntheticSessionProcessActivity
import org.desktop2fa.desktop.watch.testing.SyntheticWatchContext
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultSessionProcessDeviceTest {
    @Test fun killingAnActiveProcessDropsItsSessionAndDraftButKeepsCommittedData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ActivityManager::class.java)
        val directory = File(context.cacheDir, "synthetic-restart-${System.nanoTime()}").apply { check(mkdir()) }
        val file = File(directory, "vault.d2fa")
        var childPid: Int? = null
        fun killOwnedChild() {
            childPid?.let { pid ->
                assertNotEquals(Process.myPid(), pid)
                val process = manager.runningAppProcesses.orEmpty().singleOrNull { it.pid == pid }
                if (process != null) {
                    assertEquals("${context.packageName}:synthetic_session", process.processName)
                    assertEquals(Process.myUid(), process.uid)
                    Process.killProcess(pid)
                }
                val deadline = System.nanoTime() + 30_000_000_000L
                while (manager.runningAppProcesses.orEmpty().any { it.pid == pid } && System.nanoTime() < deadline) Thread.sleep(100)
                assertFalse("Dedicated synthetic child terminated", manager.runningAppProcesses.orEmpty().any { it.pid == pid })
            }
            childPid = null
        }
        fun child(stage: String): String {
            val marker = File(directory, "session-result.txt")
            Files.deleteIfExists(marker.toPath())
            context.startActivity(Intent(context, SyntheticSessionProcessActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("syntheticDirectory", directory.name)
                .putExtra("sessionStage", stage))
            val deadline = System.nanoTime() + 75_000_000_000L
            while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(100)
            // Record ownership even if a child fails to publish its bounded result.
            childPid = manager.runningAppProcesses.orEmpty().singleOrNull {
                it.processName == "${context.packageName}:synthetic_session"
            }?.pid
            assertTrue("Synthetic session child completed stage", marker.exists())
            val fields = marker.readText().split('|')
            assertEquals(2, fields.size)
            assertEquals(childPid, fields[0].toInt())
            return fields[1]
        }
        try {
            val password = "Synthetic-DeviceSession-42!"
            Vault.create().apply { addEntry("synthetic persisted", "synthetic", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") }.saveNew(file, password)
            val original = file.readBytes()
            assertEquals("ACTIVE_DRAFT", child("active"))
            killOwnedChild() // No finish/onDestroy or orderly draft cleanup.
            assertEquals("LOCKED_CLEAN", child("inspect"))
            killOwnedChild()
            assertArrayEquals(original, file.readBytes())
            assertEquals(listOf("synthetic persisted"), Vault.load(file, password).entries.map { it.accountName })
            assertArrayEquals(original, file.readBytes())
        } finally {
            killOwnedChild()
            check(VaultSecurityManager.clearPassword(SyntheticWatchContext(context, directory)))
            Files.newDirectoryStream(directory.toPath()).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(directory.toPath())
        }
    }
}
