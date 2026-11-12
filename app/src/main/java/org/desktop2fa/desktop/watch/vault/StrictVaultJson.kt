package org.desktop2fa.desktop.watch.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.desktop2fa.desktop.watch.common.StrictJsonText

/** Validate v2 before model decoding: the JSON decoder alone accepts duplicate keys. */
internal object StrictVaultJson {
    fun decode(bytes: ByteArray): String = StrictJsonText.decode(bytes)

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

}
