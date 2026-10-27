package org.desktop2fa.desktop.watch.vault

import org.desktop2fa.desktop.watch.vault.PasswordStrengthValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStrengthValidatorTest {

    @Test
    fun testWeakPassword() {
        val result = PasswordStrengthValidator.evaluate("123")
        assertFalse(result.isValid)
    }

    @Test
    fun testStrongPassword() {
        val result = PasswordStrengthValidator.evaluate("Str0ng!P@ssw0rd")
        assertTrue(result.isValid)
    }
}
