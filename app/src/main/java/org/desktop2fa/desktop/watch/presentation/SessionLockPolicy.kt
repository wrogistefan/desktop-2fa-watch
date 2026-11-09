package org.desktop2fa.desktop.watch.presentation

/** Monotonic time only. Focus changes, clock ticks and I/O completion are not input. */
internal class SessionLockPolicy(
    private val now: () -> Long,
    private val timeoutMillis: Long = 60_000,
) {
    private var started = false
    private var lastInput: Long? = null

    fun start() {
        started = true
        if (lastInput == null) lastInput = now()
    }

    fun stop() { started = false; lastInput = null }
    fun locked() { lastInput = null }

    fun expired(): Boolean {
        val last = lastInput ?: return false
        val current = now()
        return (current < last || current - last >= timeoutMillis).also { if (it) lastInput = null }
    }

    /** Expiration wins over the first input arriving at/after the deadline. */
    fun input(): Boolean {
        if (!started || expired()) return false
        lastInput = now()
        return true
    }

    fun remainingMillis(): Long? {
        if (!started) return null
        val last = lastInput ?: return null
        val elapsed = now() - last
        return if (elapsed < 0) 0 else (timeoutMillis - elapsed).coerceAtLeast(0)
    }
}
