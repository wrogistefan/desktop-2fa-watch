package org.desktop2fa.desktop.watch.vault

import org.desktop2fa.desktop.watch.model.TotpEntry
import org.desktop2fa.desktop.watch.model.VaultData
import org.junit.Assert.*
import org.junit.Test

class VaultLookupTest {
    private fun entry(issuer: String?, name: String?) = TotpEntry(issuer, name, "MY======")
    private fun vault(entries: List<TotpEntry>) = Vault(VaultData(entries = entries.toMutableList()))
    private fun orders(entries: List<TotpEntry>) = entries.indices.flatMap { offset ->
        val rotated = entries.drop(offset) + entries.take(offset)
        listOf(rotated, rotated.reversed())
    }
    private fun assertAmbiguousWithoutRemoval(vault: Vault, query: String) {
        val before = vault.entries
        assertThrows(AmbiguousEntryException::class.java) { vault.getEntry(query) }
        assertThrows(AmbiguousEntryException::class.java) { vault.removeEntry(query) }
        assertTrue("Ambiguity preserves every record and ordering", vault.entries == before)
    }

    @Test fun exactAccountNameWinsOverMultipleIssuerMatchesInEveryOrder() {
        val target = entry("synthetic service", "synthetic match")
        for (items in orders(listOf(entry("synthetic match", "one"), target, entry("synthetic match", "two")))) {
            assertTrue("Account name takes precedence", vault(items).getEntry("synthetic match") === target)
        }
    }

    @Test fun duplicateAccountNamesNeverFallBackToAUniqueIssuer() {
        val items = listOf(entry("one", "synthetic match"), entry("two", "synthetic match"), entry("synthetic match", "three"))
        for (ordered in orders(items)) assertAmbiguousWithoutRemoval(vault(ordered), "synthetic match")
    }

    @Test fun duplicateIssuersAreAmbiguousWithoutAnExactAccountName() {
        val items = listOf(entry("synthetic service", "one"), entry("synthetic service", "two"))
        for (ordered in orders(items)) assertAmbiguousWithoutRemoval(vault(ordered), "synthetic service")
    }

    @Test fun fullyIdenticalHistoricalCopiesAreStillAmbiguous() {
        val original = entry("synthetic service", "synthetic account")
        val data = vault(listOf(original, original.copy()))
        assertAmbiguousWithoutRemoval(data, "synthetic account")
        assertAmbiguousWithoutRemoval(data, "synthetic service")
    }

    @Test fun nullLabelsDoNotBecomeEmptyStringsOrOverrideExactEmptyNames() {
        val emptyName = entry("synthetic empty", "")
        val nullName = entry("synthetic fallback", null)
        for (ordered in orders(listOf(entry("", null), emptyName, nullName, entry(null, null)))) {
            val data = vault(ordered)
            assertTrue("Empty is a real exact account name", data.getEntry("") === emptyName)
            assertTrue("Null account supports a unique issuer fallback", data.getEntry("synthetic fallback") === nullName)
        }
    }

    @Test fun uniqueFallbackWorksAndMissingCaseSensitiveQueriesCannotRemoveData() {
        val target = entry("Syntetyczny Żółw", "synthetic account")
        val data = vault(listOf(target, entry(null, null)))
        assertTrue("Unique issuer fallback preserved", data.getEntry("Syntetyczny Żółw") === target)
        val before = data.entries
        for (query in listOf("synthetic missing", "syntetyczny żółw")) {
            assertThrows(IllegalArgumentException::class.java) { data.getEntry(query) }
            assertThrows(IllegalArgumentException::class.java) { data.removeEntry(query) }
            assertTrue("Missing/case-different queries preserve data", data.entries == before)
        }
    }

    @Test fun removalUsesTheExactNameRatherThanAnOverlappingIssuer() {
        val target = entry("synthetic other", "synthetic match")
        val retained = entry("synthetic match", "synthetic retained")
        for (ordered in orders(listOf(retained, target))) {
            val data = vault(ordered)
            data.removeEntry("synthetic match")
            assertTrue("Only the unique account-name record is removed", data.entries.size == 1 && data.entries.single() === retained)
        }
    }
}
