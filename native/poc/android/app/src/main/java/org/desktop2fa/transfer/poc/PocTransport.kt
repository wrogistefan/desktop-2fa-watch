package org.desktop2fa.transfer.poc

import kotlinx.serialization.json.*
import org.desktop2fa.desktop.watch.common.StrictJsonText
import org.desktop2fa.transfer.NativeCore
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Base64

internal class PocFailure : Exception("Transport test failed")
internal const val PROFILE = "D2FA-SPAKE2-BSSL1"

/** No accounts, vaults or input-bearing exception messages belong in this test transport. */
internal object PocMessages {
    fun message(type: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("v", 1); put("type", type); fields()
    }
    fun parse(bytes: ByteArray): JsonObject = try {
        Json.parseToJsonElement(StrictJsonText.decode(bytes)) as JsonObject
    } catch (_: Exception) { throw PocFailure() }
    fun expect(value: JsonObject, type: String, fields: Set<String>) {
        if (value.keys != fields + setOf("v", "type") || value["v"] != JsonPrimitive(1) ||
            string(value, "type") != type) throw PocFailure()
    }
    fun string(value: JsonObject, field: String): String {
        val item = value[field] as? JsonPrimitive ?: throw PocFailure()
        if (!item.isString) throw PocFailure()
        return item.content
    }
    fun id(value: JsonObject, field: String): String = string(value, field).also {
        if (!Regex("[0-9a-f]{32}").matches(it)) throw PocFailure()
    }
    fun point(value: JsonObject): ByteArray {
        val text = string(value, "message")
        if (text.length != 44) throw PocFailure()
        val raw = try { Base64.getDecoder().decode(text) } catch (_: Exception) { throw PocFailure() }
        if (raw.size != 32 || Base64.getEncoder().encodeToString(raw) != text) throw PocFailure()
        return raw
    }
    fun endpoint(text: String): Pair<InetAddress, Int> {
        val parts = text.split(':')
        if (parts.size != 2 || !Regex("[0-9]{1,5}").matches(parts[1])) throw PocFailure()
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: throw PocFailure()
        val octets = parts[0].split('.')
        if (octets.size != 4) throw PocFailure()
        val raw = octets.map {
            if (!Regex("0|[1-9][0-9]{0,2}").matches(it)) throw PocFailure()
            (it.toInt().takeIf { n -> n <= 255 } ?: throw PocFailure()).toByte()
        }.toByteArray()
        val address = InetAddress.getByAddress(raw) // Literal IPv4 only; no default-network DNS.
        if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLoopbackAddress) throw PocFailure()
        return address to port
    }
}

internal class PocFrames(
    private val now: () -> Long,
    private val timeout: (Int) -> Unit,
) {
    fun remaining(deadline: Long): Int = (deadline - now()).takeIf { it > 0 }
        ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: throw PocFailure()
    fun read(input: InputStream, deadline: Long, limit: Int): JsonObject {
        val prefix = exact(input, 4, deadline)
        val length = ByteBuffer.wrap(prefix).int
        if (length !in 1..limit) throw PocFailure()
        val payload = exact(input, length, deadline)
        return try { PocMessages.parse(payload) } finally { payload.fill(0) }
    }
    fun exact(input: InputStream, length: Int, deadline: Long): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            timeout(remaining(deadline))
            val count = input.read(bytes, offset, length - offset)
            if (count <= 0) throw PocFailure()
            offset += count
        }
        remaining(deadline) // Completion after a deadline is not success.
        return bytes
    }
    fun write(output: OutputStream, value: JsonObject, limit: Int) {
        val payload = value.toString().toByteArray(Charsets.UTF_8)
        if (payload.size !in 1..limit) throw PocFailure()
        output.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        output.write(payload); output.flush()
    }
}

/** Exact preflight reads share the socket with TLS: no buffered read-ahead is discarded. */
internal class PocTlsPipe(private val socket: Socket, private val handle: Long, private val now: () -> Long) {
    private val frames = PocFrames(now) { socket.soTimeout = it }
    private fun drain() {
        while (true) {
            val wire = NativeCore.drain(handle, 16384) ?: throw PocFailure()
            if (wire.isEmpty()) return
            socket.getOutputStream().write(wire)
        }
    }
    private fun feed(deadline: Long) {
        socket.soTimeout = frames.remaining(deadline)
        val wire = ByteArray(16384)
        val count = socket.getInputStream().read(wire)
        if (count <= 0 || NativeCore.feed(handle, wire.copyOf(count)) != count) throw PocFailure()
    }
    fun handshake(deadline: Long) {
        if (NativeCore.tlsStart(handle) != 1) throw PocFailure()
        while (true) {
            frames.remaining(deadline)
            val result = NativeCore.tlsStep(handle)
            if (result < 0) throw PocFailure()
            drain()
            if (result == 1) { frames.remaining(deadline); return }
            feed(deadline)
        }
    }
    fun write(value: JsonObject, deadline: Long) {
        val data = value.toString().toByteArray(Charsets.UTF_8)
        if (data.size !in 1..262144) throw PocFailure()
        val framed = ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array()
        try {
            var offset = 0
            while (offset < framed.size) {
                frames.remaining(deadline)
                val chunk = framed.copyOfRange(offset, minOf(offset + 16384, framed.size))
                val count = try { NativeCore.write(handle, chunk) } finally { chunk.fill(0) }
                if (count <= 0) throw PocFailure() // Memory BIO output does not block; no unsafe retry buffer.
                offset += count
                drain()
            }
        } finally { data.fill(0); framed.fill(0) }
    }
    fun read(deadline: Long): JsonObject {
        val header = exact(4, deadline)
        val length = ByteBuffer.wrap(header).int
        if (length !in 1..262144) throw PocFailure()
        val payload = exact(length, deadline)
        return try { PocMessages.parse(payload) } finally { payload.fill(0) }
    }
    private fun exact(size: Int, deadline: Long): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            frames.remaining(deadline)
            val data = NativeCore.read(handle, minOf(16384, size - offset)) ?: throw PocFailure()
            drain()
            if (data.isEmpty()) feed(deadline)
            else { data.copyInto(out, offset); offset += data.size; data.fill(0) }
        }
        frames.remaining(deadline)
        return out
    }
}
