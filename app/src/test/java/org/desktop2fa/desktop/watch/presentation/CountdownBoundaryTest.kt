package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.vault.TotpGenerationException
import org.desktop2fa.desktop.watch.vault.TotpGenerator
import org.junit.Assert.*
import org.junit.Test

class CountdownBoundaryTest {
    @Test
    fun countdownRollsOverAtTheExactPeriodWithoutWaitingForAClock() {
        for (period in listOf(1, 30, 45, 60, Int.MAX_VALUE)) {
            val entry = TotpEntry(secret = "MY======", period = period)
            fun remaining(time: Long) = (accountCodeState(entry, time) as AccountCodeState.Available).secondsRemaining
            assertEquals(period.toLong(), remaining(0))
            assertEquals(1L, remaining(period.toLong() - 1))
            assertEquals(period.toLong(), remaining(period.toLong()))
            assertEquals(if (period == 1) 1L else period - 1L, remaining(period.toLong() + 1))
        }
    }

    @Test
    fun unsupportedDigitsIncludingTenFailRatherThanExpandingTheProductRange() {
        for (digits in listOf(Int.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, 9, 10, Int.MAX_VALUE)) {
            assertThrows(TotpGenerationException::class.java) {
                TotpGenerator.generateTotp("MY======", 59, digits = digits)
            }
        }
    }
}
