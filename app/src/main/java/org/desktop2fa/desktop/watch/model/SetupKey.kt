package org.desktop2fa.desktop.watch.model

import org.bouncycastle.util.encoders.Base32

/** RFC 4648 Base32: ASCII letters, digits 2–7 and optional canonical padding. */
object SetupKey {
    // Only ASCII formatting whitespace is ignored. Never fold Unicode into a key.
    fun normalize(value: String): String = buildString {
        for (character in value) {
            when (character) {
                ' ', in '\t'..'\r' -> Unit
                in 'a'..'z' -> append(character.uppercaseChar())
                else -> append(character)
            }
        }
    }

    fun error(value: String): String? {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return "Enter the setup key"
        val invalid = normalized.firstOrNull { it !in 'A'..'Z' && it !in '2'..'7' && it != '=' }
        // Never echo user-supplied key material in error messages.
        if (invalid != null) return when (invalid) {
            '0' -> "0 is not a Base32 character; check for the letter O"
            '1' -> "1 is not a Base32 character; check the original key"
            else -> "Use only letters A–Z and numbers 2–7"
        }
        val body = normalized.trimEnd('=')
        if ('=' in body) return "Padding (=) is only allowed at the end"
        if (body.isEmpty() || body.length % 8 !in setOf(0, 2, 4, 5, 7)) {
            return "The key is incomplete; check its length"
        }
        val padded = body.padEnd(((body.length + 7) / 8) * 8, '=')
        if ('=' in normalized && normalized != padded) return "Incorrect Base32 padding"
        return try {
            val decoded = Base32.decode(padded)
            if (decoded.isEmpty() || Base32.toBase32String(decoded) != padded) {
                "The key has invalid trailing bits; check the last character"
            } else null
        } catch (_: Exception) {
            "The key is not valid Base32"
        }
    }

    /** Used for new entries and in-memory decoding, never to rewrite loaded entries. */
    fun canonical(value: String): String {
        require(error(value) == null) { "Invalid setup key" }
        val body = normalize(value).trimEnd('=')
        return body.padEnd(((body.length + 7) / 8) * 8, '=')
    }
}
