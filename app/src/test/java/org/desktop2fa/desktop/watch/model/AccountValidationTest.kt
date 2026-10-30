package org.desktop2fa.desktop.watch.model

import org.desktop2fa.desktop.watch.vault.TotpGenerator
import org.junit.Assert.*
import org.junit.Test

class AccountValidationTest {
    @Test
    fun acceptsAllSupportedParametersWithoutChangingOptionalNamesOrStoredKeys() {
        for (algorithm in listOf("SHA1", "SHA256", "SHA512")) {
            for (digits in 6..8) {
                for (period in listOf(1, 30, 45, Int.MAX_VALUE)) {
                    val entry = TotpEntry(secret = " m\ty\n ", algorithm = algorithm, digits = digits, period = period)
                    AccountValidation.requireValid(entry)
                    assertNull(entry.issuer)
                    assertNull(entry.accountName)
                    assertEquals(" m\ty\n ", entry.secret)
                }
            }
        }
    }

    @Test
    fun rejectsUnsupportedAlgorithmsDigitCountsAndPeriods() {
        val valid = TotpEntry(secret = "MY======")
        val invalid = listOf(
            valid.copy(algorithm = "MD5"), valid.copy(algorithm = "sha1"), valid.copy(algorithm = ""),
            valid.copy(digits = Int.MIN_VALUE), valid.copy(digits = 0), valid.copy(digits = 5),
            valid.copy(digits = 9), valid.copy(digits = 10), valid.copy(digits = Int.MAX_VALUE),
            valid.copy(period = 0), valid.copy(period = -1), valid.copy(period = Int.MIN_VALUE),
        )
        for (entry in invalid) {
            assertThrows(IllegalArgumentException::class.java) { AccountValidation.requireValid(entry) }
        }
    }

    @Test
    fun rejectsEmptyMalformedAndUnicodeKeysInsteadOfFoldingThemIntoAscii() {
        for (key in listOf("", " \t\n", "A", "M0", "M1", "MY=", "M=Y=====", "MZ", "MY=======",
            "\u017fy", "\u0131y", "\u00df", "\uFF2D\uFF39", "m\u00a0y", "m\u2003y")) {
            assertNotNull(SetupKey.error(key))
            assertThrows(IllegalArgumentException::class.java) {
                AccountValidation.requireValid(TotpEntry(secret = key))
            }
        }
        // Long s used to uppercase into S; validation must still see the original character.
        assertEquals("\u017fY", SetupKey.normalize("\u017fy"))
    }

    @Test
    fun acceptsAsciiFormattingWhitespaceAndEveryBase32PaddingLength() {
        for (key in listOf("MY======", "MZXQ====", "MZXW6===", "MZXW6YQ=", "MZXW6YTB")) {
            assertEquals(key, SetupKey.canonical(key.lowercase().trimEnd('=')))
            AccountValidation.requireValid(TotpEntry(secret = key))
        }
        assertEquals("MY======", SetupKey.canonical(" m\t\n\u000b\u000c\ry "))
    }

    @Test
    fun formattedStoredKeyGeneratesTheRfcCodeWithoutRewritingTheEntry() {
        // RFC 6238 Appendix B, SHA1 / 8 digits / t=59, public synthetic key.
        val secret = " gezdgnbvgy3tqojq\ngez dgnbvgy3tqojq "
        val entry = TotpEntry(secret = secret, digits = 8)
        AccountValidation.requireValid(entry)
        assertEquals("94287082", TotpGenerator.generateTotp(entry.secret, 59, entry.period, entry.digits, entry.algorithm))
        assertEquals(secret, entry.secret)
    }
}
