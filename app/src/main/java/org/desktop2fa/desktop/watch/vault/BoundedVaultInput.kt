package org.desktop2fa.desktop.watch.vault

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Read at most the limit plus one overflow sentinel, closing on every outcome. */
internal fun readVaultBytes(input: InputStream, maxBytes: Int = Vault.MAX_VAULT_SIZE): ByteArray = input.use { stream ->
    require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE) { "Invalid vault read limit" }
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes
    while (true) {
        val count = stream.read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (count < 0) break
        if (count == 0) {
            // Avoid a busy loop for a stream that makes no bulk-read progress.
            val single = stream.read()
            if (single < 0) break
            if (remaining == 0) throw UnsupportedFormatException("Vault file exceeds maximum supported size")
            output.write(single)
            remaining -= 1
        } else {
            if (count > remaining) throw UnsupportedFormatException("Vault file exceeds maximum supported size")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    output.toByteArray()
}
