package com.calypsan.listenup.api.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The kind of a Story World entity. [UNKNOWN] is what an older client decodes a newer server's kind
 * as, so one new kind never fails a sync page; such an entity renders as a generic entity.
 *
 * Serialized by [EntityKindSerializer], not the compiler-generated enum serializer, for the reason
 * [ExternalRatingSource] documents: `coerceInputValues` never reaches an enum decoded standalone.
 */
@Serializable(with = EntityKindSerializer::class)
enum class EntityKind {
    CHARACTER,
    LOCATION,
    ITEM,
    GROUP,
    PEOPLE,
    EVENT,
    CONCEPT,
    UNKNOWN,
    ;

    /** Name → kind lookup shared by the wire serializer, the server's storage read and the Room converter. */
    companion object {
        /** The kind named [name] (exact, upper-case), or [UNKNOWN] for a name this build has never heard of. */
        fun fromName(name: String): EntityKind = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** Decodes [EntityKind] by name, falling back to [EntityKind.UNKNOWN] for a literal this build doesn't know. */
object EntityKindSerializer : KSerializer<EntityKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EntityKind", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: EntityKind,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): EntityKind = EntityKind.fromName(decoder.decodeString())
}
