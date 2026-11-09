package org.desktop2fa.desktop.watch.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.desktop2fa.desktop.watch.presentation.MainActivity
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
class SyntheticMainActivity : MainActivity() {
    internal val syntheticContext by lazy {
        val name = requireNotNull(intent.getStringExtra("syntheticDirectory"))
        val directory = requireNotNull(syntheticRecoveryDirectory(cacheDir, name))
        SyntheticWatchContext(applicationContext, directory)
    }

    override fun vaultContext(): Context = syntheticContext
}
