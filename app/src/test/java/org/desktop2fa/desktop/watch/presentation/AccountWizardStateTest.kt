package org.desktop2fa.desktop.watch.presentation

import org.junit.Assert.*
import org.junit.Test

class AccountWizardStateTest {
    @Test
    fun normalizesFormattedKeysAndProducesDesktopReadablePadding() {
        assertEquals("MY", SetupKey.normalize(" m\ty\n "))
        assertNull(SetupKey.error(" m y "))
        assertEquals("MY======", SetupKey.canonical("my"))
        assertEquals("MY======", SetupKey.canonical("MY======"))
    }

    @Test
    fun acceptsEveryValidBase32Remainder() {
        for (key in listOf("MY======", "MZXQ====", "MZXW6===", "MZXW6YQ=", "MZXW6YTB")) {
            assertNull(key, SetupKey.error(key))
            assertEquals(key, SetupKey.canonical(key.trimEnd('=')))
        }
    }

    @Test
    fun rejectsIncompleteLengthsAndBadPadding() {
        for (key in listOf("A", "AAA", "AAAAAA", "=", "MY=", "MY=======", "M=Y=====")) {
            assertNotNull(key, SetupKey.error(key))
        }
    }

    @Test
    fun rejectsNonCanonicalTrailingBits() {
        assertNotNull(SetupKey.error("MZ"))
        assertNull(SetupKey.error("MY"))
    }

    @Test
    fun explainsConfusableCharactersWithoutEchoingKey() {
        assertTrue(SetupKey.error("M0")!!.startsWith("0 is not"))
        assertTrue(SetupKey.error("M1")!!.startsWith("1 is not"))
        assertEquals("Use only letters A–Z and numbers 2–7", SetupKey.error("SECRET!"))
    }

    @Test
    fun editingAnIncompleteKeyDoesNotImmediatelyShowAnError() {
        val draft = AccountWizardState(step = AccountStep.SECRET).edit(" a ")
        assertEquals("A", draft.secret)
        assertNull(draft.error)
        assertNotNull(draft.submit(emptyList()).error)
    }

    @Test
    fun backAndForwardPreserveAllDraftFields() {
        val draft = AccountWizardState().edit(" GitHub ").submit(emptyList())
            .edit(" demo@example.com ").submit(emptyList()).edit("MY")
        val previous = draft.back()
        assertEquals(AccountStep.ACCOUNT, previous.step)
        assertEquals("GitHub", previous.issuer)
        assertEquals("demo@example.com", previous.account)
        assertEquals("MY", previous.secret)
        assertEquals(draft, previous.submit(emptyList()))
        assertEquals(AccountStep.SERVICE, previous.back().step)
    }

    @Test
    fun requiresServiceAndAccountAndChecksDuplicateNamesBeforeSecretStep() {
        assertNotNull(AccountWizardState().submit(emptyList()).error)
        val account = AccountWizardState().edit("GitHub").submit(emptyList())
        assertNotNull(account.submit(emptyList()).error)
        val duplicate = account.edit(" demo ").submit(listOf("demo"))
        assertEquals(AccountStep.ACCOUNT, duplicate.step)
        assertNotNull(duplicate.error)
        assertFalse(duplicate.isSaving)
    }

    @Test
    fun rechecksDuplicateNamesBeforeClaimingSave() {
        val draft = validDraft()
        val rejected = draft.submit(listOf("demo"))
        assertEquals(AccountStep.ACCOUNT, rejected.step)
        assertFalse(rejected.isSaving)
        assertEquals(draft.secret, rejected.secret)
    }

    @Test
    fun claimPreventsRepeatedSubmissionsAndEditsWhileSaving() {
        val saving = validDraft().submit(emptyList())
        assertTrue(saving.isSaving)
        assertEquals("MY======", saving.secret)
        assertSame(saving, saving.submit(emptyList()))
        assertSame(saving, saving.edit("another key"))
        assertSame(saving, saving.back())
    }

    @Test
    fun failedSaveRetainsValidatedDraftForRetry() {
        val saving = validDraft().submit(emptyList())
        val failed = saving.copy(isSaving = false, error = "Could not save")
        val retried = failed.submit(emptyList())
        assertTrue(retried.isSaving)
        assertNull(retried.error)
        assertEquals(saving.secret, retried.secret)
        assertEquals(saving.account, retried.account)
        assertEquals(saving.issuer, retried.issuer)
    }

    private fun validDraft() = AccountWizardState().edit("GitHub").submit(emptyList())
        .edit("demo").submit(emptyList()).edit("MY")
}
