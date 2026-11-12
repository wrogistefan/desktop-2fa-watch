package org.desktop2fa.transfer.poc

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Base64

class PocTransportTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: PocFailure) {}
    }
    @Test fun strictJsonRejectsAmbiguousInputs() {
        val invalid = listOf("[]", "{\"v\":1,\"v\":1}", "{\"v\":1,\"\\u0076\":1}",
            "{\"v\":1.0}", "{\"v\":1e0}", "{\"v\":NaN}", "{\"v\":01}",
            "\ufeff{}", "{}{}", "{\"a\":" + "[".repeat(8) + "0" + "]".repeat(8) + "}")
        invalid.forEach { rejected { PocMessages.parse(it.toByteArray()) } }
        rejected { PocMessages.parse(byteArrayOf(123, 34, -1, 34, 58, 48, 125)) }
        val valid = PocMessages.parse("{\"v\":1,\"type\":\"PING\"}".toByteArray())
        PocMessages.expect(valid, "PING", emptySet())
    }
    @Test fun exactSchemaAndTypesAreRequired() {
        listOf("{\"v\":\"1\",\"type\":\"PING\"}", "{\"v\":true,\"type\":\"PING\"}",
            "{\"v\":1,\"type\":\"PING\",\"extra\":0}", "{\"v\":1,\"type\":1}").forEach {
            rejected { PocMessages.expect(PocMessages.parse(it.toByteArray()), "PING", emptySet()) }
        }
        rejected { PocMessages.id(buildJsonObject { put("sid", "A".repeat(32)) }, "sid") }
    }
    @Test fun base64MustBeCanonicalPaddedAndExactly32Bytes() {
        val good = Base64.getEncoder().encodeToString(ByteArray(32))
        assertEquals(32, PocMessages.point(buildJsonObject { put("message", good) }).size)
        listOf(good.dropLast(1), good.dropLast(2) + "B=", " ".repeat(44),
            Base64.getEncoder().encodeToString(ByteArray(33))).forEach {
            rejected { PocMessages.point(buildJsonObject { put("message", it) }) }
        }
    }
    @Test fun framesSurviveFragmentationAndPreserveFollowingBytes() {
        val buffer = ByteArrayOutputStream()
        val frames = PocFrames({ 0 }) { assertEquals(5000, it) }
        frames.write(buffer, PocMessages.message("PING"), 2048)
        frames.write(buffer, PocMessages.message("PONG"), 2048)
        val source = ByteArrayInputStream(buffer.toByteArray())
        val fragmented = object : InputStream() {
            override fun read() = source.read()
            override fun read(b: ByteArray, off: Int, len: Int) = source.read(b, off, minOf(1, len))
        }
        PocMessages.expect(frames.read(fragmented, 5000, 2048), "PING", emptySet())
        PocMessages.expect(frames.read(fragmented, 5000, 2048), "PONG", emptySet())
        assertEquals(0, source.available())
    }
    @Test fun invalidFrameLengthsAreRejectedBeforePayloadRead() {
        listOf(0, -1, 2049, Int.MAX_VALUE).forEach { size ->
            val source = ByteArrayInputStream(ByteBuffer.allocate(5).putInt(size).put(42).array())
            rejected { PocFrames({ 0 }) {}.read(source, 5000, 2048) }
            assertEquals(1, source.available())
        }
    }
    @Test fun fragmentedFramesCannotExtendDeadline() {
        var now = 0L
        val source = object : InputStream() {
            override fun read() = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                now += 2000; b[off] = 0; return 1
            }
        }
        rejected { PocFrames({ now }) {}.read(source, 5000, 2048) }
        assertEquals(6000L, now)
        rejected { PocFrames({ 5000 }) {}.exact(ByteArrayInputStream(byteArrayOf(1)), 1, 5000) }
        rejected { PocFrames({ 0 }) {}.read(ByteArrayInputStream(byteArrayOf(0)), 5000, 2048) }
    }
    @Test fun endpointUsesLiteralCanonicalIpv4Only() {
        val endpoint = PocMessages.endpoint("192.0.2.10:12345")
        assertEquals("192.0.2.10", endpoint.first.hostAddress); assertEquals(12345, endpoint.second)
        listOf("example.com:1234", "192.000.2.1:1234", "256.0.0.1:12", "0.0.0.0:1",
            "127.0.0.1:1", "224.0.0.1:1", "192.0.2.1:0", "192.0.2.1:65536", "[::1]:1234",
            "192.0.2.1:12 ").forEach { rejected { PocMessages.endpoint(it) } }
    }
}
