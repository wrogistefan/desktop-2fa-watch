package org.desktop2fa.desktop.watch.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.desktop2fa.desktop.watch.presentation.MainActivity
import org.desktop2fa.desktop.watch.presentation.VaultSessionStorage
import org.desktop2fa.desktop.watch.presentation.AccountAdditionCandidate
import org.desktop2fa.desktop.watch.vault.VaultSecurityManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only synthetic storage/pref names; never expose the real app files to a UI test. */
internal class SyntheticWatchContext(base: Context, val directory: File) : ContextWrapper(base.applicationContext) {
    @Volatile var refuseDeletion = false
    @Volatile var deletionStarted: CountDownLatch? = null
    @Volatile var deletionRelease: CountDownLatch? = null

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory
    // Deliberately model a positively absent first-use directory without creating
    // fixture files as a getter side effect. Archive tests create it explicitly.
    // Never delegate to the real application's no-backup storage.
    override fun getNoBackupFilesDir(): File = File(directory, "no-backup")
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("${directory.name}_$name", mode)

    override fun deleteSharedPreferences(name: String): Boolean {
        if (refuseDeletion) {
            deletionStarted?.countDown()
            // Controlled, bounded I/O delay: the UI must lock before this returns.
            deletionRelease?.await(30, TimeUnit.SECONDS)
            return false
        }
        return super.deleteSharedPreferences("${directory.name}_$name")
    }
}

/** Non-exported debug host uses the production screen and callbacks unchanged. */
open class SyntheticMainActivity : MainActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("syntheticKeepScreenOn", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    internal var monotonicOffset = 0L
    internal var monotonicOverride: Long? = null
    internal var wallClockOverride: Long? = null
    override fun monotonicMillis(): Long = monotonicOverride ?: (super.monotonicMillis() + monotonicOffset)
    override fun wallClockMillis(): Long = wallClockOverride ?: super.wallClockMillis()
    internal fun clockChangedForTest() = notifyWallClockChanged()
    internal var beforeIo: (String) -> Unit = {}
    internal var afterIo: (String) -> Unit = {}
    internal val syntheticContext by lazy {
        val name = requireNotNull(intent.getStringExtra("syntheticDirectory"))
        val directory = requireNotNull(syntheticRecoveryDirectory(cacheDir, name))
        SyntheticWatchContext(applicationContext, directory)
    }

    override fun vaultContext(): Context = syntheticContext

    internal override fun createSessionStorage(context: Context): VaultSessionStorage =
        object : VaultSessionStorage(File(context.filesDir, "vault.d2fa"), context.noBackupFilesDir, { VaultSecurityManager.clearPassword(context) }) {
            private fun <T> controlled(name: String, work: () -> T): T {
                beforeIo(name)
                return work().also { afterIo(name) }
            }
            override fun inspect() = controlled("startup") { super.inspect() }
            override fun create(password: String) = controlled("setup") { super.create(password) }
            override fun unlock(password: String) = controlled("unlock") { super.unlock(password) }
            override fun commit(candidate: AccountAdditionCandidate, password: String) =
                controlled("add") { super.commit(candidate, password) }
        }
}
