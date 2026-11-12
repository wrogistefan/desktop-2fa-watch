package org.desktop2fa.desktop.watch.vault

import org.junit.Assert.*
import org.junit.Test

class StrictVaultJsonTest {
    @Test fun rejectsAmbiguousJsonAndBoundsNesting() {
        val invalid = listOf(
            "{}{}", "[]", "\uFEFF{}", "{\"a\":1,\"a\":2}",
            "{\"a\":1,\"\\u0061\":2}", "{\"a\":{\"b\":1,\"b\":2}}",
            "{\"a\":1.0}", "{\"a\":1e1}", "{\"a\":01}", "{\"a\":-}",
            "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":truefalse}",
            "{\"a\":[] ,}", "{\"a\":[1,]}", "{\"a\":\"\n\"}",
            "{\"a\":\"\\q\"}", "{\"a\":\"\\u00xz\"}", "{\"a\":\"unterminated}",
            "{\"a\":" + "[".repeat(8) + "0" + "]".repeat(8) + "}",
        )
        for (text in invalid) assertThrows(Exception::class.java) { StrictVaultJson.decode(text.toByteArray()) }
        assertThrows(Exception::class.java) { StrictVaultJson.decode(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
    }

    @Test fun payloadTypesDoNotCoerceStringsBooleansOrNumbers() {
        val valid = """{"version":2,"entries":[{"secret":"MY","digits":6,"period":30}],"transfer_receipts":[]}"""
        StrictVaultJson.validatePayloadTypes(valid)
        for (bad in listOf(
            valid.replace("\"digits\":6", "\"digits\":\"6\""),
            valid.replace("\"period\":30", "\"period\":true"),
            valid.replace("\"secret\":\"MY\"", "\"secret\":123"),
            valid.replace("\"version\":2", "\"version\":\"2\""),
        )) assertThrows(IllegalArgumentException::class.java) { StrictVaultJson.validatePayloadTypes(bad) }
    }

    @Test fun acceptsEscapesUnicodeAndSeparateObjectKeyScopes() {
        val text = """ {"name":"Syntetyczny żegarek 😀","a":[{"x":null},{"x":true}],"b":-12,"c":"\"\\\/\b\f\n\r\t\u0061"} """
        assertEquals(text, StrictVaultJson.decode(text.toByteArray()))
        val atLimit = "{\"a\":" + "[".repeat(7) + "0" + "]".repeat(7) + "}"
        assertEquals(atLimit, StrictVaultJson.decode(atLimit.toByteArray()))
    }
}
