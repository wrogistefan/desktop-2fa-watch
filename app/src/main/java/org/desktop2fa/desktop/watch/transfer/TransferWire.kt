package org.desktop2fa.desktop.watch.transfer

import kotlinx.serialization.json.*
import org.desktop2fa.desktop.watch.common.StrictJsonText
import org.desktop2fa.desktop.watch.model.TransferReceipt
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Desktop-to-Watch messages only, after the native core authenticated the full TLS profile. */
internal sealed interface TransferPeerMessage {
    class Ready(val sessionId: String) : TransferPeerMessage
    class Status(val transferId: String, val sha256: String) : TransferPeerMessage
    class Offer(val offer: TransferOffer) : TransferPeerMessage {
        override fun toString() = "Offer(redacted)"
    }
    class Cancel(val transferId: String, val reason: String) : TransferPeerMessage
    class Error(val code: TransferError, val transferId: String?, ids: List<String>) : TransferPeerMessage {
        private val owned = ids.toList()
        val itemIds get() = owned.toList()
    }
    data object Ping : TransferPeerMessage
    data object Pong : TransferPeerMessage
}

/** Stateless schema/direction checks. Ordering, ownership, TLS and deadlines belong to the controller. */
internal object TransferWire {
    private val id = Regex("[0-9a-f]{32}")
    private val digest = Regex("[0-9a-f]{64}")
    private val accountErrors = setOf(TransferError.INVALID_ACCOUNT, TransferError.UNSUPPORTED_TOTP,
        TransferError.DUPLICATE_IN_BATCH, TransferError.NAME_CONFLICT, TransferError.PARAMETER_CONFLICT)
    private fun reject(code: TransferError = TransferError.INVALID_MESSAGE): Nothing = throw TransferValidationFailure(code)
    private fun string(value: JsonObject, key: String): String = (value[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: reject()
    private fun identifier(value: JsonObject, key: String): String = string(value, key).also { if (!id.matches(it)) reject() }
    private fun fields(value: JsonObject, vararg names: String) {
        if (value.keys != setOf("v", "type", *names)) reject()
    }
    private fun integer(value: JsonObject, key: String): Int = (value[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.intOrNull ?: reject()
    fun parseDesktop(payload: ByteArray): TransferPeerMessage {
        if (payload.size !in 1..TransferOffer.MAX_FRAME) reject(TransferError.LIMIT_EXCEEDED)
        // OFFER reparses its exact bytes so whitespace/escape spelling are retained in its digest.
        val owned = payload.copyOf()
        try {
            val value = try { Json.parseToJsonElement(StrictJsonText.decode(owned)) as? JsonObject }
                catch (_: Exception) { reject() } ?: reject()
            if (integer(value, "v") != 1) reject(TransferError.UNSUPPORTED_VERSION)
            return when (string(value, "type")) {
                "READY" -> {
                    fields(value, "session_id", "max_accounts", "max_frame")
                    if (integer(value, "max_accounts") != 50 || integer(value, "max_frame") != TransferOffer.MAX_FRAME) reject()
                    TransferPeerMessage.Ready(identifier(value, "session_id"))
                }
                "STATUS" -> {
                    fields(value, "transfer_id", "offer_sha256")
                    val sha = string(value, "offer_sha256"); if (!digest.matches(sha)) reject()
                    TransferPeerMessage.Status(identifier(value, "transfer_id"), sha)
                }
                "OFFER" -> TransferPeerMessage.Offer(TransferOffer.parse(owned))
                "CANCEL" -> {
                    fields(value, "transfer_id", "reason")
                    val reason = string(value, "reason"); if (reason !in setOf("user", "locked")) reject()
                    TransferPeerMessage.Cancel(identifier(value, "transfer_id"), reason)
                }
                "PING" -> { fields(value); TransferPeerMessage.Ping }
                "PONG" -> { fields(value); TransferPeerMessage.Pong }
                "ERROR" -> {
                    if (!value.keys.containsAll(setOf("v", "type", "code")) ||
                        (value.keys - setOf("v", "type", "code", "transfer_id", "item_ids")).isNotEmpty()) reject()
                    val code = try { TransferError.valueOf(string(value, "code")) } catch (_: IllegalArgumentException) { reject() }
                    val transfer = if ("transfer_id" in value) identifier(value, "transfer_id") else null
                    val ids = if ("item_ids" in value) {
                        if (code !in accountErrors) reject()
                        (value["item_ids"] as? JsonArray ?: reject()).map { item ->
                            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reject()
                            if (!id.matches(text)) reject(); text
                        }
                    } else emptyList()
                    TransferPeerMessage.Error(code, transfer, ids)
                }
                else -> reject() // Includes Watch-only and obsolete COMMIT messages.
            }
        } finally { owned.fill(0) }
    }
    private fun message(type: String, body: JsonObjectBuilder.() -> Unit = {}): ByteArray =
        buildJsonObject { put("v", 1); put("type", type); body() }.toString().toByteArray(Charsets.UTF_8)
    fun hello(sessionId: String): ByteArray {
        require(id.matches(sessionId)); return message("HELLO") { put("session_id", sessionId) }
    }
    fun ping(): ByteArray = message("PING")
    fun pong(): ByteArray = message("PONG")
    fun unknown(transferId: String): ByteArray {
        require(id.matches(transferId)); return message("UNKNOWN") { put("transfer_id", transferId) }
    }
    fun cancelled(transferId: String): ByteArray {
        require(id.matches(transferId)); return message("CANCELLED") { put("transfer_id", transferId) }
    }
    fun review(candidate: TransferAdditionCandidate): ByteArray = receipt(candidate.receipt, "REVIEW")
    /** Only a confirmed publication/read-only retained receipt result may produce RESULT. */
    fun result(saved: TransferCommitResult.Saved): ByteArray = receipt(saved.receipt, "RESULT")
    private fun receipt(receipt: TransferReceipt, type: String): ByteArray {
        require(id.matches(receipt.transferId) && digest.matches(receipt.offerSha256))
        require((receipt.addedItemIds + receipt.skippedItemIds).all(id::matches))
        return message(type) {
            put("transfer_id", receipt.transferId); put("offer_sha256", receipt.offerSha256)
            put("added_item_ids", JsonArray(receipt.addedItemIds.map(::JsonPrimitive)))
            put("skipped_item_ids", JsonArray(receipt.skippedItemIds.map(::JsonPrimitive)))
        }
    }
    fun error(code: TransferError, transferId: String? = null, itemIds: List<String>? = null): ByteArray {
        require(transferId == null || id.matches(transferId))
        require(itemIds == null || code in accountErrors && itemIds.all(id::matches))
        return message("ERROR") {
            put("code", code.name); transferId?.let { put("transfer_id", it) }
            itemIds?.let { put("item_ids", JsonArray(it.map(::JsonPrimitive))) }
        }
    }
}

internal class TransferFrameFailure : Exception("Transfer frame failed")

/** Exact reads preserve the next frame/TLS bytes. A socket owner supplies monotonic time/read timeout. */
internal class TransferFrames(private val now: () -> Long, private val readTimeout: (Int) -> Unit) {
    private fun remaining(deadline: Long): Int = (deadline - now()).takeIf { it > 0 }
        ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: throw TransferFrameFailure()
    private fun exact(input: InputStream, count: Int, deadline: Long): ByteArray {
        val bytes = ByteArray(count)
        try {
            var offset = 0
            while (offset < count) {
                readTimeout(remaining(deadline))
                val read = input.read(bytes, offset, count - offset)
                if (read <= 0) throw TransferFrameFailure()
                offset += read
            }
            remaining(deadline)
            return bytes
        } catch (_: Exception) { bytes.fill(0); throw TransferFrameFailure() }
    }
    /** Caller owns/clears the returned raw payload after parsing; limit is checked before allocation. */
    fun read(input: InputStream, deadline: Long, limit: Int = TransferOffer.MAX_FRAME): ByteArray {
        require(limit in 1..TransferOffer.MAX_FRAME)
        val prefix = exact(input, 4, deadline)
        val count = ByteBuffer.wrap(prefix).int
        if (count !in 1..limit) throw TransferFrameFailure()
        return exact(input, count, deadline)
    }
    /** The transport must enforce an independent write deadline and close a stalled socket. */
    fun write(output: OutputStream, payload: ByteArray, limit: Int = TransferOffer.MAX_FRAME) {
        require(limit in 1..TransferOffer.MAX_FRAME)
        if (payload.size !in 1..limit) throw TransferFrameFailure()
        try {
            output.write(ByteBuffer.allocate(4).putInt(payload.size).array())
            output.write(payload); output.flush()
        } catch (_: Exception) { throw TransferFrameFailure() }
    }
}
