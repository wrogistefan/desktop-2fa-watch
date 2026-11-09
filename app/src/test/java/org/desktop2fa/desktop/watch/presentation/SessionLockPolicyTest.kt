package org.desktop2fa.desktop.watch.presentation

import org.junit.Assert.*
import org.junit.Test

class SessionLockPolicyTest {
    @Test fun exactSixtySecondBoundaryExpiresBeforeInputCanExtendIt() {
        var time = 100L
        val policy = SessionLockPolicy({ time })
        policy.start()
        time += 59_999
        assertFalse(policy.expired())
        assertEquals(1L, policy.remainingMillis())
        time++
        assertFalse(policy.input())
        assertNull(policy.remainingMillis())
        assertFalse(policy.expired()) // One invalidation per expiration.
        assertTrue(policy.input()) // A new input may start a new closed-screen interaction.
        assertEquals(60_000L, policy.remainingMillis())
    }

    @Test fun deliveredEditsAndRotaryInputExtendTheDeadlineButPassiveChecksDoNot() {
        var time = 0L
        val policy = SessionLockPolicy({ time })
        policy.start()
        repeat(4) { time += 59_000; assertTrue(policy.input()) }
        time += 59_999
        repeat(10) { assertFalse(policy.expired()); assertEquals(1L, policy.remainingMillis()) }
        time++
        assertTrue(policy.expired())
    }

    @Test fun backgroundAndExplicitLockDisarmTimersWithoutRestoringOldDeadlines() {
        var time = 0L
        val policy = SessionLockPolicy({ time })
        policy.start(); policy.locked()
        assertNull(policy.remainingMillis())
        assertTrue(policy.input())
        policy.stop()
        time += 90_000
        assertFalse(policy.input())
        assertNull(policy.remainingMillis())
        policy.start()
        assertEquals(60_000L, policy.remainingMillis())
    }

    @Test fun resumeChecksElapsedTimeRatherThanStartingAFreshTimeout() {
        var time = 0L
        val policy = SessionLockPolicy({ time })
        policy.start()
        // Pause/focus loss, e.g. IME, does not call stop or start a new deadline.
        time = 60_000
        policy.start()
        assertTrue(policy.expired())
    }

    @Test fun monotonicRollbackFailsClosedAndWallClockIsNotAnInput() {
        var elapsed = 1_000L
        val policy = SessionLockPolicy({ elapsed })
        policy.start()
        elapsed--
        assertTrue(policy.expired())
    }
}
