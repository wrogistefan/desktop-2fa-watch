package org.desktop2fa.desktop.watch.transfer

import kotlinx.serialization.json.*
import org.bouncycastle.util.encoders.Base32
import org.desktop2fa.desktop.watch.common.StrictJsonText
import org.desktop2fa.desktop.watch.model.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal enum class TransferError {
    INVALID_MESSAGE, UNSUPPORTED_VERSION, INVALID_ACCOUNT, UNSUPPORTED_TOTP,
    LIMIT_EXCEEDED, DUPLICATE_IN_BATCH, NAME_CONFLICT, PARAMETER_CONFLICT, TX_CONFLICT,
    // Existing wire code: storage is invalid or not transfer-ready. No automatic retry/migration.
    STORAGE_ERROR, BUSY, VAULT_LOCKED, SESSION_EXPIRED, TOO_LATE,
}

/** Fixed diagnostics only: never attach parser exceptions or account values. */
internal class TransferValidationFailure(val code: TransferError, ids: List<String> = emptyList()) :
    Exception(code.name) {
    private val ownedIds = ids.toList()
    val itemIds: List<String> get() = ownedIds.toList()
}

internal class IncomingAccount(val itemId: String, val entry: TotpEntry) {
    override fun toString() = "IncomingAccount(redacted)"
}

/** One defensive frame snapshot binds the parsed accounts to its exact-byte digest. */
internal class TransferOffer private constructor(
    val transferId: String, val sha256: String, accounts: List<IncomingAccount>,
) {
    private val ownedAccounts = accounts.toList()
    val accounts: List<IncomingAccount> get() = ownedAccounts.toList()
    override fun toString() = "TransferOffer(redacted)"

    companion object {
        const val MAX_FRAME = 262144
        private val id = Regex("[0-9a-f]{32}")
        private val fields = setOf("item_id", "kind", "issuer", "account", "secret", "algorithm", "digits", "period")
        private fun reject(code: TransferError, item: String? = null): Nothing =
            throw TransferValidationFailure(code, item?.let(::listOf) ?: emptyList())
        private fun string(value: JsonObject, key: String, code: TransferError, item: String? = null): String {
            val field = value[key] as? JsonPrimitive ?: reject(code, item)
            if (!field.isString) reject(code, item)
            return field.content
        }
        private fun integer(value: JsonObject, key: String, item: String): Int {
            val field = value[key] as? JsonPrimitive ?: reject(TransferError.INVALID_ACCOUNT, item)
            if (field.isString) reject(TransferError.INVALID_ACCOUNT, item)
            return field.intOrNull ?: reject(TransferError.INVALID_ACCOUNT, item)
        }
        private fun name(value: String, allowEmpty: Boolean, item: String) {
            val length = try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
            } catch (_: Exception) { reject(TransferError.INVALID_ACCOUNT, item) }
            if (length !in (if (allowEmpty) 0 else 1)..256 ||
                (!allowEmpty && value.all(Char::isWhitespace)) || value.any {
                    it.isISOControl() || it.code == 0x202d || it.code == 0x202e
                }) reject(TransferError.INVALID_ACCOUNT, item)
        }
        private fun account(value: JsonElement): IncomingAccount {
            val obj = value as? JsonObject ?: reject(TransferError.INVALID_ACCOUNT)
            val item = string(obj, "item_id", TransferError.INVALID_ACCOUNT)
            if (!id.matches(item)) reject(TransferError.INVALID_ACCOUNT)
            if (obj.keys != fields) reject(TransferError.INVALID_ACCOUNT, item)
            if (string(obj, "kind", TransferError.INVALID_ACCOUNT, item) != "totp") reject(TransferError.UNSUPPORTED_TOTP, item)
            val issuer = string(obj, "issuer", TransferError.INVALID_ACCOUNT, item)
            val account = string(obj, "account", TransferError.INVALID_ACCOUNT, item)
            name(issuer, true, item); name(account, false, item)
            val secret = string(obj, "secret", TransferError.INVALID_ACCOUNT, item)
            if (secret.length !in 2..205 || !Regex("[A-Z2-7]+").matches(secret)) reject(TransferError.INVALID_ACCOUNT, item)
            val decoded = try { Base32.decode(secret.padEnd(((secret.length + 7) / 8) * 8, '=')) }
                catch (_: Exception) { reject(TransferError.INVALID_ACCOUNT, item) }
            try {
                if (decoded.size !in 1..128 || Base32.toBase32String(decoded).trimEnd('=') != secret)
                    reject(TransferError.INVALID_ACCOUNT, item)
            } finally { decoded.fill(0) }
            val algorithm = string(obj, "algorithm", TransferError.INVALID_ACCOUNT, item)
            val digits = integer(obj, "digits", item)
            val period = integer(obj, "period", item)
            if (algorithm !in setOf("SHA1", "SHA256", "SHA512") || digits !in setOf(6, 8) || period !in 1..86400)
                reject(TransferError.UNSUPPORTED_TOTP, item)
            return IncomingAccount(item, TotpEntry(issuer, account, secret, digits, period, algorithm))
        }
        fun parse(frame: ByteArray): TransferOffer {
            if (frame.size !in 1..MAX_FRAME) reject(TransferError.LIMIT_EXCEEDED)
            val owned = frame.copyOf()
            try {
                val value = try { Json.parseToJsonElement(StrictJsonText.decode(owned)) as? JsonObject }
                    catch (_: Exception) { reject(TransferError.INVALID_MESSAGE) }
                    ?: reject(TransferError.INVALID_MESSAGE)
                val version = value["v"] as? JsonPrimitive ?: reject(TransferError.INVALID_MESSAGE)
                if (version.isString || version.intOrNull == null) reject(TransferError.INVALID_MESSAGE)
                if (version.intOrNull != 1) reject(TransferError.UNSUPPORTED_VERSION)
                if (value.keys != setOf("v", "type", "transfer_id", "accounts") ||
                    string(value, "type", TransferError.INVALID_MESSAGE) != "OFFER") reject(TransferError.INVALID_MESSAGE)
                val transfer = string(value, "transfer_id", TransferError.INVALID_MESSAGE)
                if (!id.matches(transfer)) reject(TransferError.INVALID_MESSAGE)
                val array = value["accounts"] as? JsonArray ?: reject(TransferError.INVALID_MESSAGE)
                if (array.size !in 1..50) reject(TransferError.LIMIT_EXCEEDED)
                val accounts = array.map(::account)
                val repeatedIds = accounts.groupBy { it.itemId }.filterValues { it.size > 1 }.keys.toList()
                if (repeatedIds.isNotEmpty()) throw TransferValidationFailure(TransferError.DUPLICATE_IN_BATCH, repeatedIds)
                val digest = MessageDigest.getInstance("SHA-256").digest(owned)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                return TransferOffer(transfer, digest, accounts)
            } finally { owned.fill(0) }
        }
    }
}
