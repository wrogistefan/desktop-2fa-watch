package org.desktop2fa.desktop.watch.testing

import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.*
import org.desktop2fa.desktop.watch.presentation.AccountStep
import org.desktop2fa.desktop.watch.presentation.AccountWizardState
import org.desktop2fa.desktop.watch.presentation.Screen
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Debug-only process-death fixture: Intents/markers contain metadata, never credentials. */
class SyntheticSessionProcessActivity : SyntheticMainActivity() {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("syntheticDirectory") ?: return finish()
        val directory = syntheticRecoveryDirectory(cacheDir, name) ?: return finish()
        val stage = intent.getStringExtra("sessionStage") ?: return finish()
        if (stage !in setOf("active", "inspect")) return finish()
        testScope.launch {
            val result = try {
                withTimeout(60_000) {
                    while (currentSession?.let { !it.operationPending && it.currentScreen == Screen.UNLOCK } != true) delay(100)
                    val session = requireNotNull(currentSession)
                    if (stage == "active") {
                        // Public synthetic fixture constants, never a user password or key.
                        session.passwordInput = "Synthetic-DeviceSession-42!"
                        session.unlock()
                        while (session.operationPending) delay(100)
                        check(session.currentScreen == Screen.MAIN && session.activeVault != null)
                        session.addAccount()
                        session.wizardState = AccountWizardState(AccountStep.SECRET, "synthetic", "synthetic draft",
                            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
                        "ACTIVE_DRAFT"
                    } else {
                        check(session.activeVault == null && session.passwordInput.isEmpty() && session.wizardState == AccountWizardState())
                        "LOCKED_CLEAN"
                    }
                }
            } catch (_: TimeoutCancellationException) { "ERROR" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "ERROR" }
            withContext(Dispatchers.IO) {
                val pending = File(directory, "session-result.pending")
                pending.writeText("${Process.myPid()}|$result")
                Files.move(pending.toPath(), File(directory, "session-result.txt").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            // Keep this process alive with its state; the controller intentionally kills it.
        }
    }

    override fun onDestroy() {
        testScope.cancel()
        super.onDestroy()
    }
}
