package org.desktop2fa.desktop.watch.testing

import android.app.Activity
import android.os.Bundle
import android.os.Process
import org.desktop2fa.desktop.watch.vault.VaultFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

internal fun syntheticRecoveryDirectory(cache: File, name: String): File? = try {
    if (!name.matches(Regex("synthetic-restart-[0-9]+"))) null
    else {
        val root = cache.canonicalFile.toPath()
        val candidate = root.resolve(name)
        val attributes = Files.readAttributes(candidate, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) null
        else {
            val resolved = candidate.toFile().canonicalFile.toPath()
            if (resolved.parent == root) resolved.toFile() else null
        }
    }
} catch (_: Exception) { null }

/** Debug-only, non-exported host; only synthetic fixtures in a disposable cache directory. */
class SyntheticRecoveryProcessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("syntheticDirectory") ?: return finish()
        val directory = syntheticRecoveryDirectory(cacheDir, name) ?: return finish()
        val stage = intent.getStringExtra("stage") ?: return finish()
        if (stage !in setOf("beforeTemp", "afterTemp", "afterBackup", "afterCommit", "partialRestore", "inspect")) return finish()
        val result = try { prepareStage(directory, stage) } catch (_: Exception) { "ERROR" }
        // Only stage/PID metadata crosses the test boundary; no credentials or raw errors.
        try {
            val marker = File(directory, "result.pending")
            marker.writeText("${Process.myPid()}|$result")
            Files.move(marker.toPath(), File(directory, "result.txt").toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // The controller's bounded timeout reports a missing marker, without provider logs.
        } finally {
            finish()
            // Intentional crash semantics: never depend on onDestroy cleanup in this host.
            Process.killProcess(Process.myPid())
        }
    }

    private fun prepareStage(directory: File, stage: String): String {
        val main = File(directory, "synthetic.d2fa")
        val temp = File(directory, "synthetic.d2fa.tmp")
        val backup = File(directory, "synthetic.d2fa.bak")
        return if (stage == "inspect") {
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
    }
}
