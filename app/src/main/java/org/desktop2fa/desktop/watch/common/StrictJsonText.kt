package org.desktop2fa.desktop.watch.common

import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Shared lexical checks only; vault and transfer schemas remain separate. */
internal object StrictJsonText {
    fun decode(bytes: ByteArray): String {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        Parser(text).validate()
        return text
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid JSON")
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
                    // This format deliberately permits only JSON integer syntax.
                    if (pos < text.length && text[pos] in ".eE") invalid()
                    // Other adjacent values fail the next delimiter check.
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
