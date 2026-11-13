package org.desktop2fa.desktop.watch.transfer

import kotlinx.serialization.json.*
import org.desktop2fa.desktop.watch.model.TransferReceipt
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

class TransferWireTest {
    private val id = "1".repeat(32)
    private val sha = "2".repeat(64)
    private fun parse(text: String) = TransferWire.parseDesktop(text.toByteArray())
    private fun rejects(text: String, code: TransferError = TransferError.INVALID_MESSAGE) {
        try { parse(text); fail("Invalid synthetic message accepted") }
        catch (failure: TransferValidationFailure) { assertEquals(code, failure.code) }
    }
    @Test fun readyRequiresExactLimitsAndStatusPreservesIdentifiers() {
        val ready = parse("""{"v":1,"type":"READY","session_id":"$id","max_accounts":50,"max_frame":262144}""") as TransferPeerMessage.Ready
        assertEquals(id, ready.sessionId)
        val status = parse("""{"v":1,"type":"STATUS","transfer_id":"$id","offer_sha256":"$sha"}""") as TransferPeerMessage.Status
        assertEquals(id, status.transferId); assertEquals(sha, status.sha256)
        for (limits in listOf("49,262144", "50,2048", "50.0,262144", "\"50\",262144")) {
            val parts = limits.split(',')
            rejects("""{"v":1,"type":"READY","session_id":"$id","max_accounts":${parts[0]},"max_frame":${parts[1]}}""")
        }
    }
    @Test fun wrongDirectionUnknownFieldsAndObsoleteCommitAreRejected() {
        for (type in listOf("HELLO", "REVIEW", "RESULT", "UNKNOWN", "CANCELLED", "COMMIT", "other"))
            rejects("""{"v":1,"type":"$type"}""")
        rejects("""{"v":1,"type":"PING","extra":1}""")
        rejects("""{"v":1,"type":"PONG","transfer_id":"$id"}""")
        rejects("""{"v":2,"type":"PING"}""", TransferError.UNSUPPORTED_VERSION)
        rejects("""{"v":"1","type":"PING"}""")
    }
    @Test fun lexicalFailuresAndInvalidIdentifiersNeverReachTypedMessages() {
        rejects("""{"v":1,"type":"PING","t\u0079pe":"PONG"}""")
        rejects("\ufeff" + """{"v":1,"type":"PING"}""")
        rejects("""{"v":1.0,"type":"PING"}""")
        for (bad in listOf("1".repeat(31), "A".repeat(32), "x".repeat(32)))
            rejects("""{"v":1,"type":"CANCEL","transfer_id":"$bad","reason":"user"}""")
        rejects("""{"v":1,"type":"STATUS","transfer_id":"$id","offer_sha256":"short"}""")
        try { TransferWire.parseDesktop(byteArrayOf(0xc0.toByte(), 0xaf.toByte())); fail() }
        catch (failure: TransferValidationFailure) { assertEquals(TransferError.INVALID_MESSAGE, failure.code) }
    }
    @Test fun cancelAndHeartbeatsHaveExactSchemas() {
        for (reason in listOf("user", "locked")) {
            val cancel = parse("""{"v":1,"type":"CANCEL","transfer_id":"$id","reason":"$reason"}""") as TransferPeerMessage.Cancel
            assertEquals(reason, cancel.reason)
        }
        rejects("""{"v":1,"type":"CANCEL","transfer_id":"$id","reason":"timeout"}""")
        assertSame(TransferPeerMessage.Ping, TransferWire.parseDesktop(TransferWire.ping()))
        assertSame(TransferPeerMessage.Pong, TransferWire.parseDesktop(TransferWire.pong()))
    }
    @Test fun errorsAllowOnlyFixedCodesAndAccountErrorIdentifiers() {
        for (code in TransferError.entries) {
            val error = TransferWire.parseDesktop(TransferWire.error(code, id)) as TransferPeerMessage.Error
            assertEquals(code, error.code); assertEquals(id, error.transferId)
        }
        val error = TransferWire.parseDesktop(TransferWire.error(TransferError.NAME_CONFLICT, id, listOf(id))) as TransferPeerMessage.Error
        assertEquals(listOf(id), error.itemIds)
        rejects("""{"v":1,"type":"ERROR","code":"STORAGE_ERROR","item_ids":[]}""")
        rejects("""{"v":1,"type":"ERROR","code":"NAME_CONFLICT","item_ids":[null]}""")
        rejects("""{"v":1,"type":"ERROR","code":"unknown"}""")
        rejects("""{"v":1,"type":"ERROR","code":"BUSY","message":"synthetic path"}""")
    }
    @Test fun receiptMessagesContainOnlyProtocolMetadata() {
        val receipt = TransferReceipt(id, sha, listOf(id), emptyList())
        for (saved in listOf(false, true)) {
            val payload = if (saved) TransferWire.result(TransferCommitResult.Saved(receipt, null, false))
                else TransferWire.review(TransferAdditionCandidate(org.desktop2fa.desktop.watch.model.VaultData(2), emptyList(), receipt))
            val value = Json.parseToJsonElement(payload.decodeToString()).jsonObject
            assertEquals(setOf("v", "type", "transfer_id", "offer_sha256", "added_item_ids", "skipped_item_ids"), value.keys)
            assertEquals(if (saved) "RESULT" else "REVIEW", value["type"]!!.jsonPrimitive.content)
        }
        assertEquals("HELLO", Json.parseToJsonElement(TransferWire.hello(id).decodeToString()).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("UNKNOWN", Json.parseToJsonElement(TransferWire.unknown(id).decodeToString()).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("CANCELLED", Json.parseToJsonElement(TransferWire.cancelled(id).decodeToString()).jsonObject["type"]!!.jsonPrimitive.content)
    }
    @Test fun offerDigestBindsExactPayloadAndSurvivesCallerBufferClearing() {
        val payload = """{ "v":1,"type":"OFFER","transfer_id":"$id","accounts":[{"item_id":"$id","kind":"totp","issuer":"synthetic","account":"synthetic account","secret":"AA","algorithm":"SHA1","digits":6,"period":30}]}""".toByteArray()
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val parsed = TransferWire.parseDesktop(payload) as TransferPeerMessage.Offer
        payload.fill(0)
        assertEquals(expected, parsed.offer.sha256); assertEquals("AA", parsed.offer.accounts.single().entry.secret)
        assertFalse(parsed.toString().contains("AA"))
    }
    private fun framed(payload: ByteArray) = ByteBuffer.allocate(4).putInt(payload.size).array() + payload
    @Test fun fragmentedFramesPreserveFollowingBytesWithoutReadAhead() {
        val payload = TransferWire.ping(); val next = byteArrayOf(3, 4, 5)
        val source = ByteArrayInputStream(framed(payload) + next)
        val fragmented = object : InputStream() {
            override fun read() = source.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int) = source.read(bytes, offset, minOf(length, 1))
        }
        var timeouts = 0
        val frames = TransferFrames({ 0 }) { assertEquals(100, it); timeouts++ }
        assertArrayEquals(payload, frames.read(fragmented, 100))
        assertArrayEquals(next, source.readBytes()); assertTrue(timeouts > 4)
        val output = ByteArrayOutputStream(); frames.write(output, payload)
        assertArrayEquals(framed(payload), output.toByteArray())
    }
    @Test fun invalidFrameLengthsAreRejectedBeforeAnyPayloadRead() {
        for (length in listOf(0, -1, Int.MAX_VALUE, 262145)) {
            val prefix = ByteArrayInputStream(ByteBuffer.allocate(4).putInt(length).array())
            val guarded = object : InputStream() {
                override fun read(): Int = throw AssertionError("Unexpected single read")
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    if (prefix.available() == 0) throw AssertionError("Invalid length requested payload")
                    return prefix.read(bytes, offset, count)
                }
            }
            try { TransferFrames({ 0 }) {}.read(guarded, 100); fail() } catch (_: TransferFrameFailure) {}
        }
    }
    @Test fun truncationAndDeadlineExpiryFailWithoutAnUnboundedLoop() {
        for (bytes in listOf(byteArrayOf(0, 0), ByteBuffer.allocate(4).putInt(5).array() + byteArrayOf(1))) {
            try { TransferFrames({ 0 }) {}.read(ByteArrayInputStream(bytes), 100); fail() } catch (_: TransferFrameFailure) {}
        }
        var time = 0L
        val frames = TransferFrames({ time }) { time = 101 }
        try { frames.read(ByteArrayInputStream(framed(TransferWire.ping())), 100); fail() } catch (_: TransferFrameFailure) {}
        try { TransferFrames({ 100 }) {}.read(ByteArrayInputStream(byteArrayOf()), 100); fail() } catch (_: TransferFrameFailure) {}
    }
    @Test fun maximumFrameAndConfiguredPreflightLimitAreEnforced() {
        val payload = ByteArray(262144) { 1 }
        assertArrayEquals(payload, TransferFrames({ 0 }) {}.read(ByteArrayInputStream(framed(payload)), 100))
        try { TransferFrames({ 0 }) {}.read(ByteArrayInputStream(framed(ByteArray(2049))), 100, 2048); fail() }
        catch (_: TransferFrameFailure) {}
        try { TransferFrames({ 0 }) {}.write(ByteArrayOutputStream(), byteArrayOf()); fail() }
        catch (_: TransferFrameFailure) {}
    }
}
