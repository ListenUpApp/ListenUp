package com.calypsan.listenup.api.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Where a listener's own rating came from. [LISTENUP] is every rating a person set or edited in
 * ListenUp; [HARDCOVER] is one the Hardcover pull imported and the person hasn't touched since.
 * Decoded leniently ([ListenerRatingSourceSerializer]): a source this build doesn't know is a
 * plain ListenUp rating, never a decode failure.
 */
@Serializable(with = ListenerRatingSourceSerializer::class)
enum class ListenerRatingSource {
    LISTENUP,
    HARDCOVER,
}

/** Decodes [ListenerRatingSource] by name, falling back to [ListenerRatingSource.LISTENUP] for an unknown literal. */
object ListenerRatingSourceSerializer : KSerializer<ListenerRatingSource> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ListenerRatingSource", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ListenerRatingSource,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ListenerRatingSource {
        val name = decoder.decodeString()
        return ListenerRatingSource.entries.firstOrNull { it.name == name } ?: ListenerRatingSource.LISTENUP
    }
}
