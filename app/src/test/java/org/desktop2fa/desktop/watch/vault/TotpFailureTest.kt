package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Test

class TotpFailureTest {
    @Test
    fun unsupportedParametersProduceControlledFailureRatherThanASubstituteCode() {
        val failures = listOf<() -> String>(
            { TotpGenerator.generateTotp("MY======", 59, algorithm = "MD5") },
            { TotpGenerator.generateTotp("MY======", 59, algorithm = "sha1") },
            { TotpGenerator.generateTotp("MY======", 59, digits = 5) },
            { TotpGenerator.generateTotp("MY======", 59, digits = 9) },
            { TotpGenerator.generateTotp("MY======", 59, digits = 10) },
            { TotpGenerator.generateTotp("MY======", 59, period = 0) },
            { TotpGenerator.generateTotp("MY======", 59, period = -1) },
            { TotpGenerator.generateTotp("MY======", -1) },
            { TotpGenerator.generateTotp("", 59) },
            { TotpGenerator.generateTotp("MZ", 59) },
            { TotpGenerator.generateTotp("synthetic-invalid!", 59) },
        )
        for (generate in failures) {
            val error = assertThrows(TotpGenerationException::class.java) { generate() }
            assertEquals("TOTP code is unavailable", error.message)
            assertNull(error.cause)
        }
    }

    @Test
    fun knownAlgorithmsKeepTheirRfcCodesAtFixedTime() {
        // Public synthetic RFC 6238 SHA1/SHA256/SHA512 keys at t=59 / 8 digits.
        // https://www.rfc-editor.org/rfc/rfc6238.html#appendix-B
        val vectors = listOf(
            Triple("SHA1", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "94287082"),
            Triple("SHA256", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA====", "46119246"),
            Triple("SHA512", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA=", "90693936"),
        )
        for ((algorithm, secret, expected) in vectors) {
            assertEquals(expected, TotpGenerator.generateTotp(secret, 59, digits = 8, algorithm = algorithm))
        }
    }
}
