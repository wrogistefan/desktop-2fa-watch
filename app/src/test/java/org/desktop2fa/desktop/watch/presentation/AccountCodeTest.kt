package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.model.TotpEntry
import org.junit.Assert.*
import org.junit.Test

class AccountCodeTest {
    @Test
    fun invalidEntryDoesNotCrashOrReplaceAValidCodeAndHasNoCountdown() {
        val valid = TotpEntry(secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", period = 30, digits = 8)
        val states = listOf(valid.copy(period = 0), valid, valid.copy(algorithm = "MD5"))
            .map { accountCodeState(it, 59) }
        assertSame(AccountCodeState.Unavailable, states.first())
        assertEquals(AccountCodeState.Available("94287082", 1), states[1])
        assertSame(AccountCodeState.Unavailable, states.last())
    }

    @Test
    fun countdownUsesTheActualPositivePeriod() {
        val entry = TotpEntry(secret = "MY======", period = 45, digits = 7, algorithm = "SHA256")
        val state = accountCodeState(entry, 59) as AccountCodeState.Available
        assertEquals(7, state.code.length)
        assertEquals(31L, state.secondsRemaining)
        assertEquals(45L, (accountCodeState(entry, 90) as AccountCodeState.Available).secondsRemaining)
    }
}
