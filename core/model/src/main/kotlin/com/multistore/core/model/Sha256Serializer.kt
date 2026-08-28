package com.multistore.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `Sha256` as a hexadecimal string.
 *
 * Needed because [Sha256] is a `value class` with a **private constructor**: the only ways in are
 * `parseOrNull` and `ofBytes`, which normalise. A generated serializer would bypass that
 * normalisation, which is the only reason the type exists.
 *
 * A value that is not a valid digest makes deserialisation **fail** rather than produce `null`.
 * Where this serializer is used — the pinned F-Droid certificate in the remote configuration — an
 * unreadable digest must cost that store's whole override, not silently become "no pin".
 */
object Sha256Serializer : KSerializer<Sha256> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.multistore.core.model.Sha256", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Sha256) = encoder.encodeString(value.hex)

    override fun deserialize(decoder: Decoder): Sha256 {
        val raw = decoder.decodeString()
        return Sha256.parseOrNull(raw)
            ?: throw SerializationException(
                "Not a ${Sha256.HEX_LENGTH}-character hexadecimal SHA-256: $raw",
            )
    }
}
