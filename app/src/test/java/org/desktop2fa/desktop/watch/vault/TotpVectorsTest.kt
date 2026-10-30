package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private object RfcKeys {
    val secrets = mapOf(
        "SHA1" to "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        "SHA256" to "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA====",
        "SHA512" to "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA=",
    )
}

@RunWith(Parameterized::class)
class TotpRfcVectorsTest(
    private val algorithm: String,
    private val time: Long,
    private val digits: Int,
    private val expected: String,
) {
    @Test
    fun matchesPublishedVectorIncludingDecimalPadding() {
        assertEquals(expected, TotpGenerator.generateTotp(RfcKeys.secrets.getValue(algorithm), time, digits = digits, algorithm = algorithm))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} t={1} digits={2}")
        fun cases(): Collection<Array<Any>> {
            // RFC 6238 Appendix B: 18 published eight-digit cases.
            // https://www.rfc-editor.org/rfc/rfc6238.html#appendix-B
            val rows = listOf(
                59L to listOf("94287082", "46119246", "90693936"),
                1111111109L to listOf("07081804", "68084774", "25091201"),
                1111111111L to listOf("14050471", "67062674", "99943326"),
                1234567890L to listOf("89005924", "91819424", "93441116"),
                2000000000L to listOf("69279037", "90698825", "38618901"),
                20000000000L to listOf("65353130", "77737706", "47863826"),
            )
            return rows.flatMap { (time, codes) ->
                listOf("SHA1", "SHA256", "SHA512").flatMapIndexed { index, algorithm ->
                    (6..8).map { digits -> arrayOf<Any>(algorithm, time, digits, codes[index].takeLast(digits)) }
                }
            }
        }
    }
}

@RunWith(Parameterized::class)
class TotpBoundaryVectorsTest(
    private val algorithm: String,
    private val time: Long,
    private val period: Int,
    private val digits: Int,
    private val expected: String,
) {
    @Test
    fun matchesIndependentPythonExpectedValue() {
        assertEquals(expected, TotpGenerator.generateTotp(RfcKeys.secrets.getValue(algorithm), time, period, digits, algorithm))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} t={1} period={2} digits={3}")
        fun cases(): Collection<Array<Any>> {
            val text = TotpBoundaryVectorsTest::class.java.getResourceAsStream("/totp/extra-v1.json")!!.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val fixture = Json.parseToJsonElement(text).jsonObject
            require(fixture.getValue("version").jsonPrimitive.int == 1)
            val rows = fixture.getValue("cases").jsonArray
            require(rows.size == 162) { "Incomplete synthetic TOTP boundary matrix" }
            return rows.map { element ->
                val row = element.jsonArray
                arrayOf<Any>(row[0].jsonPrimitive.content, row[1].jsonPrimitive.long,
                    row[2].jsonPrimitive.int, row[3].jsonPrimitive.int, row[4].jsonPrimitive.content)
            }
        }
    }
}
