package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Validate v2 before model decoding: the JSON decoder alone accepts duplicate keys. */
internal object StrictVaultJson {
    fun decode(bytes: ByteArray): String {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        Parser(text).validate()
        return text
    }

    // Kotlin serialization accepts quoted numeric literals for Int fields. Require
    // their actual JSON types before model decoding; do not weaken v1 compatibility.
    fun validatePayloadTypes(text: String) {
        val root = Json.parseToJsonElement(text) as JsonObject
        fun invalid(): Nothing = throw IllegalArgumentException("Invalid vault JSON types")
        fun integer(value: kotlinx.serialization.json.JsonElement?) {
            val p = value as? JsonPrimitive ?: invalid()
            if (p.isString || !Regex("-?(0|[1-9][0-9]*)").matches(p.content)) invalid()
        }
        fun string(value: kotlinx.serialization.json.JsonElement?, nullable: Boolean = false) {
            if (nullable && value === JsonNull) return
            if ((value as? JsonPrimitive)?.isString != true) invalid()
        }
        integer(root["version"])
        val entries = root["entries"] as? JsonArray ?: invalid()
        val receipts = root["transfer_receipts"] as? JsonArray ?: invalid()
        for (entry in entries) {
            val obj = entry as? JsonObject ?: invalid()
            string(obj["secret"])
            for (name in listOf("issuer", "account_name")) if (name in obj) string(obj[name], true)
            if ("algorithm" in obj) string(obj["algorithm"])
            for (name in listOf("digits", "period")) if (name in obj) integer(obj[name])
        }
        for (receipt in receipts) {
            val obj = receipt as? JsonObject ?: invalid()
            string(obj["transfer_id"]); string(obj["offer_sha256"])
            for (name in listOf("added_item_ids", "skipped_item_ids")) {
                val ids = obj[name] as? JsonArray ?: invalid()
                for (id in ids) string(id)
            }
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid vault JSON")
        private fun whitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }
        private fun consume(c: Char): Boolean {
            whitespace()
            if (pos < text.length && text[pos] == c) { pos++; return true }
            return false
        }
        private fun expect(c: Char) { if (!consume(c)) invalid() }

        fun validate() {
            whitespace()
            if (pos >= text.length || text[pos] != '{') invalid()
            value(0)
            whitespace()
            if (pos != text.length) invalid()
        }

        private fun value(depth: Int) {
            whitespace()
            if (pos >= text.length) invalid()
            when (text[pos]) {
                '{' -> {
                    if (depth >= 8) invalid()
                    pos++
                    val keys = HashSet<String>()
                    if (!consume('}')) {
                        do {
                            whitespace()
                            if (!keys.add(string())) invalid()
                            expect(':')
                            value(depth + 1)
                        } while (consume(','))
                        expect('}')
                    }
                }
                '[' -> {
                    if (depth >= 8) invalid()
                    pos++
                    if (!consume(']')) {
                        do { value(depth + 1) } while (consume(','))
                        expect(']')
                    }
                }
                '"' -> { string() }
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> {
                    if (text[pos] == '-') pos++
                    if (pos >= text.length) invalid()
                    if (text[pos] == '0') pos++
                    else {
                        if (text[pos] !in '1'..'9') invalid()
                        while (pos < text.length && text[pos] in '0'..'9') pos++
                    }
                    // Fractions, exponents and adjacent values fail the next delimiter check.
                }
                else -> invalid()
            }
        }

        private fun literal(word: String) {
            if (!text.startsWith(word, pos)) invalid()
            pos += word.length
        }

        private fun string(): String {
            val start = pos
            if (pos >= text.length || text[pos++] != '"') invalid()
            while (pos < text.length) {
                when (val c = text[pos++]) {
                    '"' -> return Json.decodeFromString<String>(text.substring(start, pos))
                    '\\' -> {
                        if (pos >= text.length) invalid()
                        when (text[pos++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> {
                                repeat(4) {
                                    if (pos >= text.length || text[pos++] !in "0123456789abcdefABCDEF") invalid()
                                }
                            }
                            else -> invalid()
                        }
                    }
                    else -> if (c.code < 32) invalid()
                }
            }
            invalid()
        }
    }
}
