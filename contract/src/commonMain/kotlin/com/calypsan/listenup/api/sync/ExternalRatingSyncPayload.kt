package com.calypsan.listenup.api.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An outside catalog whose readers rate books. [UNKNOWN] is what an older client decodes a newer
 * server's source as, so one new source never fails a sync page.
 *
 * Serialized by [ExternalRatingSourceSerializer], not the compiler-generated enum serializer:
 * `contractJson`'s `coerceInputValues` only substitutes a class *property's* declared default when
 * an enum literal is unrecognised — it never reaches an enum decoded standalone, because
 * `decodeEnum` throws on an unmatched literal unconditionally. A small string-backed serializer
 * that falls back to [UNKNOWN] covers both the wrapped (a payload field) and the bare decode path.
 */
@Serializable(with = ExternalRatingSourceSerializer::class)
enum class ExternalRatingSource {
    AUDIBLE,
    HARDCOVER,
    GOODREADS,
    UNKNOWN,
}

/**
 * Decodes [ExternalRatingSource] by name, falling back to [ExternalRatingSource.UNKNOWN] for a
 * literal this build has never heard of (an additive source added by a newer server).
 */
object ExternalRatingSourceSerializer : KSerializer<ExternalRatingSource> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ExternalRatingSource", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ExternalRatingSource,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ExternalRatingSource {
        val name = decoder.decodeString()
        return ExternalRatingSource.entries.firstOrNull { it.name == name } ?: ExternalRatingSource.UNKNOWN
    }
}

/**
 * How one outside catalog rates one book — the `book_external_ratings` sync row. Written only by the
 * server; readable by everyone who can open [bookId]. [enabled] is false when an admin has switched
 * [source] off: the row is kept (switching it back on restores the score at once) but clients leave
 * it out of the headline. Health detail (last error) never crosses the wire; the fetch time does, as
 * [fetchedAt], so a client can say how fresh a score is.
 */
@Serializable
@SerialName("ExternalRatingSyncPayload")
data class ExternalRatingSyncPayload(
    /** Opaque per-row sync identity; encodes neither [bookId] nor [source]. */
    @SerialName("id") override val id: String,
    /** The rated book. */
    @SerialName("bookId") val bookId: String,
    /** The catalog the rating comes from. */
    @SerialName("source") val source: ExternalRatingSource,
    /** The catalog's average, 0–5. */
    @SerialName("average") val average: Double,
    /** How many ratings the average is over. */
    @SerialName("count") val count: Int,
    /** False when an admin has switched this source off. */
    @SerialName("enabled") val enabled: Boolean,
    /** Sync revision. */
    @SerialName("revision") override val revision: Long,
    /** Tombstone instant, else null. */
    @SerialName("deletedAt") override val deletedAt: Long? = null,
    /**
     * When the server last fetched this rating from [source] (epoch ms), for "Updated 3 days ago".
     * Null from a server older than this field, and on a tombstone.
     */
    @SerialName("fetchedAt") val fetchedAt: Long? = null,
) : SyncPayload
