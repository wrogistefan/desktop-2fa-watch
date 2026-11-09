package org.desktop2fa.desktop.watch.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.vault.CorruptedVaultException
import org.desktop2fa.desktop.watch.vault.InvalidPasswordException
import org.desktop2fa.desktop.watch.vault.PasswordStrengthValidator
import org.desktop2fa.desktop.watch.vault.RecoveryCandidate
import org.desktop2fa.desktop.watch.vault.RecoveryView
import org.desktop2fa.desktop.watch.vault.UnsupportedFormatException
import org.desktop2fa.desktop.watch.vault.Vault
import org.desktop2fa.desktop.watch.vault.VaultFiles
import org.desktop2fa.desktop.watch.vault.VaultSaveOutcome
import org.desktop2fa.desktop.watch.vault.openRecoveryView
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Non-secret, process-local handoff; never a persisted receipt or active session. */
internal class LateSessionNotice(val message: String)

/** Synchronous storage boundary; the session always calls it on its I/O dispatcher. */
internal open class VaultSessionStorage(private val file: File, private val clearCache: () -> Boolean) {
    private val noticeKey = file.absoluteFile.toPath().normalize().toString()
    fun recordNotice(message: String): LateSessionNotice = LateSessionNotice(message).also { notices[noticeKey] = it }
    fun pendingNotice(): LateSessionNotice? = notices[noticeKey]
    fun acknowledgeNotice(notice: LateSessionNotice) { notices.remove(noticeKey, notice) }
    open fun clearCredentials(): Boolean = clearCache()
    open fun inspect(): VaultStartup = resolveVaultStartup(file)
    open fun create(password: String): Vault = Vault.create().also { it.saveNew(file, password) }
    open fun unlock(password: String): Vault = Vault.load(file, password)
    open fun inventory(): List<RecoveryCandidate> = VaultFiles.inventory(file).candidates
    open fun recover(candidate: RecoveryCandidate, password: String): RecoveryView =
        openRecoveryView(file, candidate, password)
    open fun add(snapshot: VaultData, draft: AccountWizardState, password: String): AccountSaveResult {
        // The caller holds processOperations. Another live owner may have committed
        // since this UI snapshot was captured; never replace that newer data.
        if (Vault.load(file, password).data != snapshot) return AccountSaveResult(VaultSaveOutcome.NOT_COMMITTED)
        val candidate = Vault(snapshot)
        candidate.addEntry(draft.account, draft.issuer, draft.secret)
        return persistAccountCandidate(candidate, file, password)
    }
    companion object { private val notices = ConcurrentHashMap<String, LateSessionNotice>() }
}

/** In-memory, composition-owned state. Call actions on the UI dispatcher only. */
internal class VaultSession(
    private val scope: CoroutineScope,
    private val storage: VaultSessionStorage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val stallNoticeDelayMillis: Long = 30_000,
) {
    var activeVault by mutableStateOf<Vault?>(null)
        private set
    var currentScreen by mutableStateOf(Screen.SPLASH)
        private set
    var passwordInput by mutableStateOf("")
    var errorMessage by mutableStateOf<String?>(null)
    var successMessage by mutableStateOf<String?>(null)
        private set
    var cacheWarning by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var operationPending by mutableStateOf(false)
        private set
    var clearingCache by mutableStateOf(false)
        private set
    var operationNotice by mutableStateOf<String?>(null)
        private set
    var recoverySource by mutableStateOf<String?>(null)
        private set
    var recoveryCandidates by mutableStateOf(emptyList<RecoveryCandidate>())
        private set
    var wizardState by mutableStateOf(AccountWizardState())
    var addedAccountIndex by mutableStateOf<Int?>(null)
        private set

    private var sessionPassword = ""
    private var presentedNotice: LateSessionNotice? = null
    @Volatile private var generation = Any()
    @Volatile private var disposed = false
    private var cleanupRequested = false
    @Volatile private var mutationInFlight = false

    private fun owns(token: Any) = !disposed && generation === token

    private fun clearSensitiveState() {
        activeVault = null
        sessionPassword = ""
        passwordInput = ""
        wizardState = AccountWizardState()
        recoverySource = null
        recoveryCandidates = emptyList()
        addedAccountIndex = null
        successMessage = null
    }

    /** Invalidates before clearing UI. A synchronous write already in progress may still commit. */
    fun lock() {
        if (disposed) return
        val manualUnlock = recoverySource == null && currentScreen in
            setOf(Screen.MAIN, Screen.ADD_ACCOUNT, Screen.UNLOCK)
        generation = Any()
        clearSensitiveState()
        isLoading = false
        currentScreen = if (manualUnlock) Screen.UNLOCK else Screen.STORAGE_ERROR
        errorMessage = if (mutationInFlight) LATE_SAVE_REVIEW else null
        cleanupRequested = true
        if (!operationPending) cleanup()
    }

    /** Called before the composition scope is cancelled; never persist a session or draft. */
    fun dispose() {
        generation = Any()
        disposed = true
        clearSensitiveState()
        currentScreen = Screen.UNLOCK
        isLoading = false
        operationNotice = null
    }

    private fun review(message: String) {
        clearSensitiveState()
        currentScreen = Screen.STORAGE_ERROR
        errorMessage = message
    }

    private fun <T> operation(
        loading: Boolean = true,
        mutation: Boolean = false,
        work: () -> T,
        success: (T) -> Unit,
        failure: (Exception) -> Unit,
        late: (T) -> String = { LATE_SAVE_REVIEW },
    ) {
        if (disposed || operationPending) return
        val token = generation
        operationPending = true
        isLoading = loading
        mutationInFlight = false
        operationNotice = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var completionNotice: LateSessionNotice? = null
            // A notice is bounded; it never times out/unlocks a synchronous writer.
            val notice = scope.launch {
                delay(stallNoticeDelayMillis)
                if (!disposed && operationPending) operationNotice = STALLED_OPERATION
            }
            try {
                val caller = currentCoroutineContext().job
                val result = withContext(if (mutation) io + NonCancellable else io) {
                    // Do not start queued work after cancellation or Lock. Once a synchronous
                    // write starts, let its existing atomic-write protocol finish.
                    processOperations.withLock {
                        caller.ensureActive()
                        if (!owns(token)) throw CancellationException("Session closed")
                        mutationInFlight = mutation
                        if (mutation) completionNotice = storage.recordNotice(LATE_SAVE_REVIEW)
                        work().also { if (mutation) completionNotice = storage.recordNotice(late(it)) }
                    }
                }
                if (owns(token)) success(result)
                else if (!disposed && mutation) review(late(result))
                if (!disposed) completionNotice?.let { storage.acknowledgeNotice(it) }
            } catch (cancelled: CancellationException) {
                if (owns(token)) {
                    generation = Any()
                    review("Operation cancelled. Review storage before trying again.")
                }
                throw cancelled
            } catch (caught: Exception) {
                if (owns(token)) failure(caught)
                else if (!disposed && mutation) review(LATE_SAVE_REVIEW)
                if (!disposed) completionNotice?.let { storage.acknowledgeNotice(it) }
            } finally {
                notice.cancel()
                if (!disposed) {
                    operationNotice = null
                    operationPending = false
                    isLoading = false
                    mutationInFlight = false
                    if (cleanupRequested) cleanup()
                }
            }
        }
    }

    private fun cleanup() {
        cleanupRequested = false
        clearingCache = true
        operation(loading = false, work = { storage.clearCredentials() }, success = {
            clearingCache = false
            cacheWarning = credentialCleanupWarning(it)
        }, failure = {
            clearingCache = false
            cacheWarning = credentialCleanupWarning(false)
        })
    }

    fun startup() {
        if (disposed || operationPending) return
        val acknowledged = presentedNotice
        operation(work = {
            // A fresh/recreated owner only presents this notice. A subsequent explicit
            // Retry acknowledges it; merely assigning a screen is not user observation.
            acknowledged?.let { storage.acknowledgeNotice(it) }
            // No credential reads or writes, even if cleanup fails or legacy preferences remain.
            val cleared = try { storage.clearCredentials() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            Triple(storage.inspect(), cleared, storage.pendingNotice())
        }, success = { (startup, cleared, notice) ->
            clearSensitiveState()
            currentScreen = if (notice == null) startup.screen else Screen.STORAGE_ERROR
            errorMessage = listOfNotNull(startup.errorMessage, notice?.message).joinToString("\n").ifBlank { null }
            cacheWarning = credentialCleanupWarning(cleared)
            presentedNotice = notice
        }, failure = {
            cacheWarning = credentialCleanupWarning(false)
            review("Cannot check vault storage. Existing data has not been replaced.")
        })
    }

    fun setup() {
        if (operationPending || disposed) return
        val supplied = passwordInput
        val strength = PasswordStrengthValidator.evaluate(supplied)
        if (supplied.isBlank() || !strength.isValid || strength.score < 3) {
            errorMessage = if (supplied.isBlank()) "Password cannot be empty" else strength.feedback
            return
        }
        passwordInput = ""
        operation(mutation = true, work = { storage.create(supplied) }, success = {
            passwordInput = ""
            activeVault = it
            sessionPassword = supplied
            currentScreen = Screen.MAIN
            errorMessage = null
        }, failure = {
            review("Could not create a new vault. Existing data has not been replaced.")
        }, late = { "Vault was created. Review storage and unlock with its password." })
    }

    fun unlock() {
        if (operationPending || disposed) return
        val supplied = passwordInput
        if (supplied.isBlank()) { errorMessage = "Password cannot be empty"; return }
        passwordInput = ""
        operation(work = { storage.unlock(supplied) }, success = {
            passwordInput = ""
            activeVault = it
            sessionPassword = supplied
            currentScreen = Screen.MAIN
            errorMessage = null
        }, failure = {
            errorMessage = when (it) {
                is InvalidPasswordException -> "Invalid password"
                is CorruptedVaultException -> "Vault contains invalid or unsupported data"
                is UnsupportedFormatException -> "Unsupported vault format"
                else -> "Could not unlock the vault. Check its password and storage."
            }
        })
    }

    fun listRecovery() = operation(work = { storage.inventory() }, success = {
        recoveryCandidates = it
        passwordInput = ""
        errorMessage = null
        currentScreen = Screen.RECOVERY
    }, failure = { errorMessage = "Recovery files could not be inspected. No files were changed." })

    fun openRecovery(candidate: RecoveryCandidate) {
        if (operationPending || disposed) return
        val supplied = passwordInput
        passwordInput = ""
        operation(work = { storage.recover(candidate, supplied) }, success = {
            passwordInput = ""
            activeVault = it.vault
            recoverySource = it.sourceLabel
            sessionPassword = ""
            recoveryCandidates = emptyList()
            errorMessage = null
            currentScreen = Screen.MAIN
        }, failure = {
            errorMessage = "Cannot open this file. Check its password and supported data. No files were changed."
        })
    }

    fun leaveRecovery() {
        if (operationPending || disposed) return
        clearSensitiveState()
        errorMessage = null
        currentScreen = Screen.STORAGE_ERROR
    }

    fun addAccount() {
        if (operationPending || disposed || recoverySource != null || activeVault == null) return
        wizardState = AccountWizardState()
        addedAccountIndex = null
        errorMessage = null
        successMessage = null
        currentScreen = Screen.ADD_ACCOUNT
    }

    fun cancelDraft() {
        if (operationPending || disposed) return
        wizardState = AccountWizardState()
        currentScreen = Screen.MAIN
    }

    fun submitAccount() {
        if (operationPending || disposed || recoverySource != null) return
        val vault = activeVault ?: return
        val draft = wizardState.submit(vault.entries.map { it.accountName })
        wizardState = draft
        if (!draft.isSaving) return
        val supplied = sessionPassword
        // Capture on the UI thread, before entering I/O; no Compose state is read there.
        val snapshot = vault.data.copy(entries = vault.entries.toMutableList())
        operation(loading = false, mutation = true, work = {
            storage.add(snapshot, draft, supplied)
        }, success = { result ->
            wizardState = AccountWizardState()
            if (result.requiresReview) review(result.message)
            else {
                val reloaded = requireNotNull(result.vault)
                activeVault = reloaded
                addedAccountIndex = reloaded.entries.lastIndex
                successMessage = result.message
                errorMessage = null
                currentScreen = Screen.MAIN
            }
        }, failure = { review(LATE_SAVE_REVIEW) }, late = {
            if (it.requiresReview) it.message
            else "Account was saved. Unlock and review before adding it again."
        })
    }

    companion object {
        // A recreated Activity must not read a snapshot while its old owner's
        // non-interruptible write is still finishing. This is process-local only;
        // the shared mutation/candidate API remains the separate #36 task.
        private val processOperations = Mutex()
        const val STALLED_OPERATION = "Storage is still busy. You can lock now. If it does not finish, force stop and reopen the app or restart the watch. Do not clear app data; a write may have completed."
        const val LATE_SAVE_REVIEW = "A previous save may have changed storage. Review existing data before trying again."
    }
}

internal fun credentialCleanupWarning(success: Boolean): String? = if (success) null else
    "Could not confirm removal of old remembered credentials. This version ignores them; older versions may still use them."
