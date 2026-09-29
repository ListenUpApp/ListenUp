package com.calypsan.listenup.api.dto.admin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Why a rating source cannot run at all, as opposed to failing when it runs. [UNKNOWN] is what an
 * older client decodes a newer server's reason as.
 *
 * Serialized by [RatingSourceUnavailableSerializer] for the same reason as
 * `ExternalRatingSource`: the compiler-generated enum serializer throws on an unmatched literal.
 */
@Serializable(with = RatingSourceUnavailableSerializer::class)
enum class RatingSourceUnavailable {
    /** The server has no client id for the source (Hardcover: `hardcover.clientId` is blank). */
    NOT_CONFIGURED,

    /** Nobody on the server has connected an account the source needs. */
    NO_CONNECTION,

    /** A newer server named a reason this client does not know. */
    UNKNOWN,
}

/**
 * Decodes [RatingSourceUnavailable] by name, falling back to [RatingSourceUnavailable.UNKNOWN]
 * for a literal this build has never heard of.
 */
object RatingSourceUnavailableSerializer : KSerializer<RatingSourceUnavailable> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RatingSourceUnavailable", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RatingSourceUnavailable,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): RatingSourceUnavailable {
        val name = decoder.decodeString()
        return RatingSourceUnavailable.entries.firstOrNull { it.name == name } ?: RatingSourceUnavailable.UNKNOWN
    }
}
