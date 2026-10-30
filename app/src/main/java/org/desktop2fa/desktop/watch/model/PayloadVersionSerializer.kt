package org.desktop2fa.desktop.watch.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Read the version as an integer literal, without JSON string/number coercion. */
internal object PayloadVersionSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("VaultPayloadVersion", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Vault payload version must be an integer literal")
        val version = primitive.takeUnless { it.isString }?.intOrNull
        if (version == null || primitive.content != version.toString()) {
            throw SerializationException("Vault payload version must be an integer literal")
        }
        return version
    }
}
