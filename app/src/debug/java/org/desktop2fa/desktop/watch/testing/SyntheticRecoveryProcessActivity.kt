package org.desktop2fa.desktop.watch.testing

import android.app.Activity
import android.os.Bundle
import android.os.Process
import org.desktop2fa.desktop.watch.vault.VaultFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Debug-only, non-exported host; only synthetic fixtures in a disposable cache directory. */
class SyntheticRecoveryProcessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("syntheticDirectory") ?: return finish()
        if (!name.matches(Regex("synthetic-restart-[0-9]+"))) return finish()
        val directory = File(cacheDir, name)
        if (directory.parentFile?.canonicalFile != cacheDir.canonicalFile || !directory.isDirectory) return finish()
        val stage = intent.getStringExtra("stage") ?: return finish()
        if (stage !in setOf("beforeTemp", "afterTemp", "afterBackup", "afterCommit", "partialRestore", "inspect")) return finish()
        val main = File(directory, "synthetic.d2fa")
        val temp = File(directory, "synthetic.d2fa.tmp")
        val backup = File(directory, "synthetic.d2fa.bak")
        val result = if (stage == "inspect") {
            VaultFiles.inspect(main).name
        } else {
            File(directory, "old.fixture").copyTo(main)
            if (stage != "beforeTemp") File(directory, "new.fixture").copyTo(temp)
            if (stage in setOf("afterBackup", "afterCommit", "partialRestore")) main.copyTo(backup)
            if (stage == "afterCommit") Files.move(temp.toPath(), main.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            if (stage == "partialRestore") main.writeBytes(byteArrayOf(1, 2, 3))
            stage
        }
        // Only stage/PID metadata crosses the test boundary; no credentials in Intents or logs.
        val marker = File(directory, "result.pending")
        marker.writeText("${Process.myPid()}|$result")
        Files.move(marker.toPath(), File(directory, "result.txt").toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        finish()
        Process.killProcess(Process.myPid())
    }
}
