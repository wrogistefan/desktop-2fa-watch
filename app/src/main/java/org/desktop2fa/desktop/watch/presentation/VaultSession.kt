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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.desktop2fa.desktop.watch.model.TransferReceipt
import org.desktop2fa.desktop.watch.model.VaultData
import org.desktop2fa.desktop.watch.vault.VaultMigrationConsent
import org.desktop2fa.desktop.watch.vault.VaultMigrationResult
import org.desktop2fa.desktop.watch.vault.migrateVaultToV2
import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.model.SetupKey
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
import org.desktop2fa.desktop.watch.vault.migrationAwareInventory
import org.desktop2fa.desktop.watch.vault.openMigrationAwareRecoveryView
import org.desktop2fa.desktop.watch.vault.VaultPresence
import org.desktop2fa.desktop.watch.vault.VaultIOException
import org.desktop2fa.desktop.watch.transfer.TransferAdditionCandidate
import org.desktop2fa.desktop.watch.transfer.TransferCancellation
import org.desktop2fa.desktop.watch.transfer.TransferCommitResult
import org.desktop2fa.desktop.watch.transfer.TransferError
import org.desktop2fa.desktop.watch.transfer.TransferOffer
import org.desktop2fa.desktop.watch.transfer.TransferPlanner
import org.desktop2fa.desktop.watch.transfer.TransferPreparation
import org.desktop2fa.desktop.watch.transfer.TransferSaveGate
import org.desktop2fa.desktop.watch.transfer.TransferValidationFailure
import org.desktop2fa.desktop.watch.transfer.commitApprovedTransfer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One local v1-to-v2 review. Never persist this object or derive consent from a Boolean preference. */
internal class TransferMigrationReview(data: VaultData) {
    private val owned = data.copy(entries = data.entries.toMutableList(),
        transferReceipts = data.transferReceipts.map { it.snapshot() })
    fun baseline(): VaultData = owned.copy(entries = owned.entries.toMutableList(),
        transferReceipts = owned.transferReceipts.map { it.snapshot() })
    override fun toString() = "TransferMigrationReview(redacted)"
}

/** An authenticated read-only receipt query; Unknown is not proof of no historical save. */
internal sealed interface TransferStatusResult {
    class Found(receipt: TransferReceipt) : TransferStatusResult {
        private val owned = receipt.snapshot()
        val receipt get() = owned.snapshot()
        override fun toString() = "Found(redacted)"
    }
    data object Unknown : TransferStatusResult
    class Failed(val code: TransferError) : TransferStatusResult
}

/** Non-secret, process-local handoff; never a persisted receipt or active session. */
internal class LateSessionNotice(val message: String)

/** Synchronous storage boundary; the session always calls it on its I/O dispatcher. */
internal open class VaultSessionStorage(
    private val file: File, private val noBackupDirectory: File?, private val clearCache: () -> Boolean,
) {
    constructor(file: File, clearCache: () -> Boolean) : this(file, null, clearCache)
    private fun presence(): VaultPresence = noBackupDirectory?.let { migrationAwareInventory(file, it).presence }
        ?: VaultFiles.inspect(file)
    private val noticeKey = file.absoluteFile.toPath().normalize().toString()
    fun recordNotice(message: String): LateSessionNotice = LateSessionNotice(message).also { notices[noticeKey] = it }
    fun pendingNotice(): LateSessionNotice? = notices[noticeKey]
    fun acknowledgeNotice(notice: LateSessionNotice) { notices.remove(noticeKey, notice) }
    open fun clearCredentials(): Boolean = clearCache()
    open fun inspect(): VaultStartup = resolveVaultStartup(file) { presence() }
    open fun create(password: String): Vault {
        // The session calls this under its writer mutex; recheck before CREATE_NEW.
        if (presence() != VaultPresence.MISSING) throw VaultIOException("Existing recovery data requires review")
        return Vault.create().also { it.saveNew(file, password) }
    }
    open fun unlock(password: String): Vault = Vault.load(file, password)
    open fun inventory(): List<RecoveryCandidate> = noBackupDirectory?.let {
        migrationAwareInventory(file, it).candidates
    } ?: VaultFiles.inventory(file).candidates
    open fun recover(candidate: RecoveryCandidate, password: String): RecoveryView =
        noBackupDirectory?.let { openMigrationAwareRecoveryView(file, it, candidate, password) }
            ?: openRecoveryView(file, candidate, password)
    open fun commit(candidate: AccountAdditionCandidate, password: String): AccountSaveResult {
        // The caller holds processOperations. Another live owner may have committed
        // since this UI snapshot was captured; never replace that newer data.
        if (!candidate.matches(Vault.load(file, password).data)) return AccountSaveResult(VaultSaveOutcome.NOT_COMMITTED)
        return persistAccountCandidate(candidate.vault(), file, password)
    }
    open fun activateTransfer(baseline: VaultData, password: String): VaultMigrationResult {
        val archiveRoot = noBackupDirectory ?: throw VaultIOException("Migration safety storage is unavailable")
        if (presence() != VaultPresence.PRESENT) throw VaultIOException("Existing storage requires review")
        return migrateVaultToV2(file, archiveRoot, password, baseline, VaultMigrationConsent.CONFIRMED)
    }
    private fun loadTransferVault(password: String): Vault {
        if (presence() != VaultPresence.PRESENT) throw TransferValidationFailure(TransferError.STORAGE_ERROR)
        val actual = Vault.load(file, password)
        if (presence() != VaultPresence.PRESENT) throw TransferValidationFailure(TransferError.STORAGE_ERROR)
        return actual
    }
    open fun prepareTransfer(offer: TransferOffer, password: String): TransferPreparation =
        TransferPlanner.prepare(loadTransferVault(password).data, offer)
    open fun transferStatus(transferId: String, sha256: String, password: String): TransferReceipt? {
        TransferPlanner.requireIdentifiers(transferId, sha256) // Cheap validation before file inspection/KDF.
        return TransferPlanner.status(loadTransferVault(password).data, transferId, sha256)
    }
    open fun commitTransfer(candidate: TransferAdditionCandidate, password: String, approval: TransferSaveGate): TransferCommitResult =
        commitApprovedTransfer(candidate, file, password, approval)
    companion object { private val notices = ConcurrentHashMap<String, LateSessionNotice>() }
}

/** In-memory, composition-owned state. Call actions on the UI dispatcher only. */
internal class VaultSession(
    private val scope: CoroutineScope,
    private val storage: VaultSessionStorage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val stallNoticeDelayMillis: Long = 30_000,
    private val beforePublish: () -> Unit = {},
    private val onLocked: () -> Unit = {},
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

    var transferStatus by mutableStateOf<TransferStatusResult?>(null)
        private set
    var transferMigrationReview by mutableStateOf<TransferMigrationReview?>(null)
        private set
    var transferReview by mutableStateOf<TransferAdditionCandidate?>(null)
        private set
    var transferResult by mutableStateOf<TransferCommitResult?>(null)
        private set
    var transferError by mutableStateOf<TransferError?>(null)
        private set
    private class TransferOwnerToken { var closedCode: TransferError? = null }
    private var transferEpoch = TransferOwnerToken()
    private val transferUpdates = MutableStateFlow(0L)
    val transferChanges: StateFlow<Long> = transferUpdates.asStateFlow()
    private fun notifyTransferChanged() { transferUpdates.value += 1 }
    fun transferOwnerError(token: Any): TransferError? {
        val owner = token as? TransferOwnerToken ?: return TransferError.VAULT_LOCKED
        return owner.closedCode ?: if (owner !== transferEpoch || disposed) TransferError.VAULT_LOCKED else null
    }
    private var transferOpen = false
    private var transferOffer: TransferOffer? = null
    private var transferApproval: TransferSaveGate? = null
    private var transferCancelRequested = false

    private var sessionPassword = ""
    private var presentedNotice: LateSessionNotice? = null
    @Volatile private var generation = Any()
    @Volatile private var disposed = false
    private var cleanupRequested = false
    @Volatile private var mutationInFlight = false

    private fun owns(token: Any) = !disposed && generation === token
    fun uiToken(): Any = generation
    fun acceptsUiToken(token: Any): Boolean = owns(token)

    private fun clearTransferState(reason: TransferError = TransferError.VAULT_LOCKED) {
        transferEpoch.closedCode = reason
        transferStatus = null
        transferMigrationReview = null
        transferApproval?.cancel()
        transferApproval = null
        transferEpoch = TransferOwnerToken()
        transferOpen = false
        transferOffer = null
        transferReview = null
        transferResult = null
        transferError = null
        transferCancelRequested = false
        notifyTransferChanged()
    }

    private fun clearSensitiveState(reason: TransferError = TransferError.VAULT_LOCKED) {
        clearTransferState(reason)
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
        transferApproval?.cancel() // Linearize Lock against the publisher before invalidating UI ownership.
        val manualUnlock = recoverySource == null && currentScreen in
            setOf(Screen.MAIN, Screen.ADD_ACCOUNT, Screen.UNLOCK)
        generation = Any()
        clearSensitiveState()
        isLoading = false
        currentScreen = if (manualUnlock) Screen.UNLOCK else Screen.STORAGE_ERROR
        errorMessage = if (mutationInFlight) LATE_SAVE_REVIEW else null
        onLocked()
        cleanupRequested = true
        if (!operationPending) cleanup()
    }

    /** Called before the composition scope is cancelled; never persist a session or draft. */
    fun dispose() {
        transferApproval?.cancel()
        generation = Any()
        disposed = true
        clearSensitiveState()
        currentScreen = Screen.UNLOCK
        isLoading = false
        operationNotice = null
    }

    private fun review(message: String) {
        clearSensitiveState(TransferError.STORAGE_ERROR)
        currentScreen = Screen.STORAGE_ERROR
        errorMessage = message
    }

    private fun settleNotice(notice: LateSessionNotice?) {
        if (disposed || notice == null) return
        if (currentScreen == Screen.STORAGE_ERROR) presentedNotice = notice
        else storage.acknowledgeNotice(notice)
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
                if (!disposed) beforePublish()
                if (owns(token)) success(result)
                else if (!disposed && mutation) review(late(result))
                settleNotice(completionNotice)
            } catch (cancelled: CancellationException) {
                if (owns(token)) {
                    generation = Any()
                    review("Operation cancelled. Review storage before trying again.")
                } else if (!disposed && completionNotice != null) review(LATE_SAVE_REVIEW)
                settleNotice(completionNotice)
                throw cancelled
            } catch (caught: Exception) {
                if (!disposed) beforePublish()
                if (owns(token)) failure(caught)
                else if (!disposed && mutation) review(LATE_SAVE_REVIEW)
                settleNotice(completionNotice)
            } finally {
                notice.cancel()
                if (!disposed) {
                    operationNotice = null
                    operationPending = false
                    isLoading = false
                    mutationInFlight = false
                    if (cleanupRequested) cleanup()
                    notifyTransferChanged()
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
        if (operationPending || disposed || transferOpen || transferMigrationReview != null || recoverySource != null || activeVault == null) return
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
        if (operationPending || disposed || transferOpen || transferMigrationReview != null || recoverySource != null) return
        val vault = activeVault ?: return
        val draft = wizardState.submit(vault.entries.map { it.accountName })
        wizardState = draft
        if (!draft.isSaving) return
        submitAdditions(listOf(TotpEntry(issuer = draft.issuer, accountName = draft.account,
            secret = SetupKey.canonical(draft.secret))))
    }

    /** In-memory additions only; callers resolve consent/collisions before submission. */
    fun submitAdditions(additions: List<TotpEntry>) {
        if (operationPending || disposed || transferOpen || transferMigrationReview != null || recoverySource != null) return
        val vault = activeVault ?: return
        val candidate = try {
            AccountAdditionCandidate(vault.data, additions)
        } catch (_: IllegalArgumentException) {
            wizardState = wizardState.copy(isSaving = false, error = "Invalid account data")
            errorMessage = "Invalid account data. No accounts were saved."
            return
        }
        val supplied = sessionPassword
        // Capture on the UI thread, before entering I/O; no Compose state is read there.
        operation(loading = false, mutation = true, work = {
            storage.commit(candidate, supplied)
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

    /** Only an explicit local action requests this review; unlock never calls it. */
    fun requestTransferMigration(): TransferMigrationReview? {
        if (disposed || operationPending || transferOpen || transferMigrationReview != null ||
            currentScreen != Screen.MAIN || recoverySource != null || sessionPassword.isEmpty()) return null
        val data = activeVault?.data ?: return null
        if (data.version != 1) return null
        return TransferMigrationReview(data).also { transferMigrationReview = it }
    }

    fun cancelTransferMigration(review: TransferMigrationReview): Boolean {
        if (disposed || operationPending || transferMigrationReview !== review) return false
        transferMigrationReview = null
        return true
    }

    /**
     * A fresh local confirmation of the exact review; never called from network traffic.
     * Returns only whether asynchronous work was accepted, never migration success.
     * Completion updates activeVault/successMessage or enters storage review; a closed
     * owner receives only the existing late-save notice, never a reopened session.
     */
    fun confirmTransferMigration(consent: TransferMigrationReview): Boolean {
        if (disposed || operationPending || transferMigrationReview !== consent || transferOpen ||
            currentScreen != Screen.MAIN || recoverySource != null || sessionPassword.isEmpty()) return false
        val baseline = consent.baseline()
        val supplied = sessionPassword
        transferMigrationReview = null
        operation(loading = false, mutation = true, work = {
            storage.activateTransfer(baseline, supplied)
        }, success = { result ->
            if (result.requiresReview) review("Transfer activation could not be confirmed cleanly. Review existing storage and its safety copy.")
            else {
                activeVault = requireNotNull(result.vault)
                successMessage = "Transfer storage enabled. The encrypted v1 safety copy is retained; older apps cannot read the new main vault."
                errorMessage = null
            }
        }, failure = {
            review("Transfer activation failed. Existing storage and any safety copy were preserved; review storage before trying again.")
        }, late = { result ->
            if (result.requiresReview) "Transfer activation may have changed storage. Review its main vault and safety copy."
            else "Transfer storage was enabled. Unlock and review the new main vault and retained v1 safety copy."
        })
        return true
    }

    private fun writableTransferSession(): Boolean = !disposed && recoverySource == null &&
        activeVault?.data?.version == 2 && sessionPassword.isNotEmpty()

    /** Call only for explicit local activation/new authenticated receive owner, never a remote retry. */
    fun beginTransfer(): Any? {
        if (operationPending || transferOpen || transferMigrationReview != null || currentScreen != Screen.MAIN || !writableTransferSession()) return null
        clearTransferState()
        transferOpen = true
        return transferEpoch
    }

    /** Only the retained receive owner calls this after a full authenticated replacement TLS channel. */
    fun resumeTransferOwner(token: Any): Boolean {
        if (token !== transferEpoch || disposed || operationPending || !writableTransferSession() || currentScreen != Screen.MAIN)
            return false
        transferApproval = null
        transferOffer = null
        transferReview = null
        transferStatus = null
        transferResult = null
        transferError = null
        transferCancelRequested = false
        transferOpen = true
        notifyTransferChanged()
        return true
    }

    /** UI-dispatcher only; incoming traffic must separately check the inactivity deadline. */
    fun acceptsTransferToken(token: Any): Boolean = token === transferEpoch && writableTransferSession() &&
        currentScreen == Screen.MAIN && transferResult !== TransferCommitResult.Cancelled &&
        transferResult !is TransferCommitResult.Unconfirmed && transferError == null

    /** Returns asynchronous acceptance only. No write, approval, migration or receipt FIFO refresh. */
    fun queryTransferStatus(token: Any, transferId: String, sha256: String): Boolean {
        if (!acceptsTransferToken(token) || operationPending) return false
        transferStatus = null
        val supplied = sessionPassword
        operation(loading = false, work = { storage.transferStatus(transferId, sha256, supplied) }, success = {
            if (acceptsTransferToken(token)) transferStatus = it?.let(TransferStatusResult::Found) ?: TransferStatusResult.Unknown
        }, failure = {
            if (acceptsTransferToken(token)) transferStatus = TransferStatusResult.Failed(
                (it as? TransferValidationFailure)?.code ?: TransferError.STORAGE_ERROR)
        })
        return true
    }

    /** UI-dispatcher action. Network callers must not count packets as local user input. */
    fun prepareTransfer(token: Any, offer: TransferOffer): Boolean {
        if (token !== transferEpoch || !transferOpen || operationPending || !writableTransferSession()) return false
        val previous = transferOffer
        if (previous != null && (previous !== offer || transferResult !== TransferCommitResult.Stale)) return false
        transferOffer = offer
        transferReview = null
        transferResult = null
        transferError = null
        val supplied = sessionPassword
        operation(loading = false, work = { storage.prepareTransfer(offer, supplied) }, success = {
            if (token === transferEpoch && transferOpen) when (it) {
                is TransferPreparation.Review -> transferReview = it.candidate
                is TransferPreparation.AlreadySaved -> {
                    transferResult = TransferCommitResult.Saved(it.receipt, null, false)
                    transferOffer = null
                    transferOpen = false
                }
            }
        }, failure = {
            if (token === transferEpoch && transferOpen) {
                transferOffer = null
                transferReview = null
                transferOpen = false
                transferError = (it as? TransferValidationFailure)?.code ?: TransferError.STORAGE_ERROR
            }
        })
        return true
    }

    /** The candidate identity is the consent token: an old REVIEW cannot approve a replacement. */
    fun approveTransfer(token: Any, candidate: TransferAdditionCandidate): Boolean {
        if (token !== transferEpoch || !transferOpen || operationPending || !writableTransferSession() ||
            transferReview !== candidate || transferOffer == null) return false
        val supplied = sessionPassword
        val approval = TransferSaveGate()
        transferApproval = approval
        transferReview = null
        transferResult = null
        operation(loading = false, mutation = true, work = {
            storage.commitTransfer(candidate, supplied, approval)
        }, success = { result ->
            if (token === transferEpoch) {
                transferApproval = null
                val actual = if (transferCancelRequested && result === TransferCommitResult.Stale)
                    TransferCommitResult.Cancelled else result
                when (actual) {
                    TransferCommitResult.Stale -> transferResult = actual // Same OFFER may be re-prepared, never auto-approved.
                    TransferCommitResult.Cancelled -> {
                        transferOffer = null; transferOpen = false; transferResult = actual
                    }
                    is TransferCommitResult.Saved -> {
                        if (actual.requiresStorageReview) review("Transfer was saved. Storage recovery files require review.")
                        else actual.vault?.let { activeVault = it }
                        transferOffer = null; transferOpen = false; transferResult = actual
                    }
                    is TransferCommitResult.Unconfirmed -> {
                        review("Could not confirm the transfer result. Review storage before trying again.")
                        transferResult = actual
                    }
                }
            }
        }, failure = {
            if (token === transferEpoch) {
                review("Could not confirm the transfer result. Review storage before trying again.")
                transferError = TransferError.STORAGE_ERROR
            }
        }, late = {
            when (it) {
                is TransferCommitResult.Saved -> "Transfer was saved. Unlock and review before trying again."
                TransferCommitResult.Cancelled, TransferCommitResult.Stale -> "This transfer did not start a save. Unlock before another attempt."
                is TransferCommitResult.Unconfirmed -> LATE_SAVE_REVIEW
            }
        })
        return true
    }

    /** A request signal during saving; only transferResult establishes a terminal cancellation. */
    fun cancelTransfer(token: Any): TransferCancellation? {
        if (disposed || token !== transferEpoch) return null
        if (transferResult is TransferCommitResult.Saved || transferResult is TransferCommitResult.Unconfirmed)
            return TransferCancellation.TOO_LATE
        val approval = transferApproval
        if (approval != null) return approval.cancel().also {
            if (it == TransferCancellation.CANCELLED) transferCancelRequested = true
        }
        if (!transferOpen && transferResult !== TransferCommitResult.Cancelled) return null
        transferOpen = false
        transferStatus = null
        transferOffer = null
        transferReview = null
        transferResult = TransferCommitResult.Cancelled
        notifyTransferChanged()
        return TransferCancellation.CANCELLED
    }

    companion object {
        // A recreated Activity must not read a snapshot while its old owner's
        // non-interruptible write is still finishing. This is process-local only;
        // all manual/batch additions use this same lifecycle-owned operation.
        private val processOperations = Mutex()
        const val STALLED_OPERATION = "Storage is still busy. You can lock now. If it does not finish, force stop and reopen the app or restart the watch. Do not clear app data; a write may have completed."
        const val LATE_SAVE_REVIEW = "A previous save may have changed storage. Review existing data before trying again."
    }
}

internal fun credentialCleanupWarning(success: Boolean): String? = if (success) null else
    "Could not confirm removal of old remembered credentials. This version ignores them; older versions may still use them."
