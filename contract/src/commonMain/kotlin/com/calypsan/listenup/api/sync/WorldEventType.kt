package com.calypsan.listenup.api.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a Story World event says happened. Which entities may fill its subject and object, and whether it
 * needs text or takes a detail, is [com.calypsan.listenup.domain.storyworld.WorldEventRules]'s to say.
 *
 * Not [EntityKind.EVENT]: that is an *entity* — a named happening such as a battle — which events can
 * mention. An event is a line in a world's log; an EVENT entity is something the log talks about.
 *
 * [UNKNOWN] is what an older client decodes a newer server's type as, so one new type never fails a sync
 * page; such an event renders as a note. Serialized by [WorldEventTypeSerializer] for the reason
 * [ExternalRatingSource] documents.
 */
@Serializable(with = WorldEventTypeSerializer::class)
enum class WorldEventType {
    /** Free text with no further typed meaning. */
    NOTE,

    /** The subject enters the scene; the object, if any, is where. */
    ENTERS_SCENE,

    /** The subject leaves the scene; the object, if any, is where from. */
    EXITS_SCENE,

    /** The subject moves to the object, a location. */
    MOVES_TO,

    /** The subject departs; the object, if any, is the place left. */
    DEPARTS,

    /** A character (subject) joins a group (object); the detail is the role taken, if any. */
    JOINS,

    /** A character (subject) leaves a group (object); the detail is how, if said. */
    LEAVES,

    /**
     * A character (subject) is, or is revealed to be, of a people (object). Anchored at a book's start it is
     * known from the start; anchored later it is a reveal, hidden until the listener reaches it.
     */
    BELONGS_TO,

    /** Reserved: the subject takes another name. Stored; no typed rules yet. */
    ALIAS,

    /** Reserved: the subject is born. */
    BORN,

    /** Reserved: the subject dies. */
    DIES,

    /** Reserved: an item passes between subject and object. */
    ITEM_TRANSFER,

    /** Reserved: the relationship between subject and object changes. */
    RELATIONSHIP_CHANGE,

    /** A type this build doesn't know. Refused on create; an edit carrying it keeps the stored type. */
    UNKNOWN,
    ;

    /** Name → type lookup shared by the wire serializer, the server's storage read and the Room converter. */
    companion object {
        /** The type named [name] (exact, upper-case), or [UNKNOWN] for a name this build has never heard of. */
        fun fromName(name: String): WorldEventType = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** Decodes [WorldEventType] by name, falling back to [WorldEventType.UNKNOWN] for a literal this build doesn't know. */
object WorldEventTypeSerializer : KSerializer<WorldEventType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WorldEventType", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: WorldEventType,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): WorldEventType = WorldEventType.fromName(decoder.decodeString())
}
