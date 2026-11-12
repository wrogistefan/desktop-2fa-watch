package org.desktop2fa.desktop.watch.presentation

import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.model.VaultData
import org.junit.Assert.*
import org.junit.Test

class AccountAdditionCandidateTest {
    private val old = TotpEntry(null, null, KEY)
    private val added = TotpEntry("synthetic", "synthetic added", KEY, 8, 60, "SHA512")

    @Test fun capturesPrivateCopiesAndPreservesHistoricalNamesAndParameters() {
        val baseline = VaultData(entries = mutableListOf(old, old))
        val incoming = mutableListOf(added)
        val candidate = AccountAdditionCandidate(baseline, incoming)
        baseline.entries.clear(); incoming.clear()
        val first = candidate.vault()
        assertEquals(listOf(old, old, added), first.entries)
        first.data.entries.clear()
        assertEquals(listOf(old, old, added), candidate.vault().entries)
        assertTrue(candidate.matches(VaultData(entries = mutableListOf(old, old))))
        assertFalse(candidate.matches(baseline))
    }

    @Test fun rejectsEveryInvalidAccountBeforeExposingAnyCandidate() {
        val invalid = listOf(added.copy(secret = "!"), added.copy(digits = 10),
            added.copy(period = 0), added.copy(algorithm = "MD5"))
        for (bad in invalid) {
            val baseline = VaultData(entries = mutableListOf(old))
            assertThrows(IllegalArgumentException::class.java) {
                AccountAdditionCandidate(baseline, listOf(added, bad))
            }
            assertEquals(listOf(old), baseline.entries)
        }
        assertThrows(IllegalArgumentException::class.java) { AccountAdditionCandidate(VaultData(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AccountAdditionCandidate(VaultData(99), listOf(added)) }
        assertThrows(IllegalArgumentException::class.java) {
            AccountAdditionCandidate(VaultData(entries = mutableListOf(old.copy(period = 0))), listOf(added))
        }
    }

    companion object { private const val KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" }
}
