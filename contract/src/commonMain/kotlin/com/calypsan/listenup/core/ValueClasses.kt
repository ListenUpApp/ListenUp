@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Type-safe wrapper for server URLs with built-in validation and normalization.
 *
 * Validates URL format (must start with http:// or https://) and normalizes
 * by removing the trailing slash. Client-only — the server doesn't need to
 * know which server URL the client is pointed at.
 */
@Serializable
@JvmInline
value class ServerUrl(
    val raw: String,
) {
    init {
        require(raw.isNotBlank()) { "Server URL cannot be blank" }
        require(raw.startsWith("http://") || raw.startsWith("https://")) {
            "Server URL must start with http:// or https://, got: $raw"
        }
    }

    /** Normalized form — trailing slash removed. */
    val value: String get() = raw.trimEnd('/')

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Book IDs.
 *
 * Provides compile-time type safety to prevent accidentally passing wrong ID types
 * (e.g., user IDs, instance IDs) where book IDs are expected.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying book ID string (e.g., "book-abc123")
 */
@Serializable
@JvmInline
value class BookId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Book ID cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Create BookId from string value.
         * Validates that value is not blank.
         */
        fun fromString(value: String): BookId = BookId(value)
    }
}

/**
 * Type-safe wrapper for Library IDs.
 *
 * A library is the root path the scanner walks; every book belongs to exactly
 * one. Wrapping the id prevents it being confused with a [BookId] or any other
 * string id at call sites that thread both — notably the scanner's
 * `resolveOrInsert(libraryId, analyzed)`.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying library ID string.
 */
@JvmInline
value class LibraryId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Library ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Chapter IDs.
 */
@JvmInline
value class ChapterId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Chapter ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Series IDs.
 */
@JvmInline
value class SeriesId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Series ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Contributor IDs.
 */
@JvmInline
value class ContributorId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Contributor ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for PlaybackPosition IDs.
 *
 * Identifies a single per-user position row in the `playback_positions` table.
 * Each row represents the current resume point for one `(userId, bookId)` pair;
 * the id is a stable UUID assigned on first write and reused on every update.
 */
@Serializable
@JvmInline
value class PlaybackPositionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "PlaybackPosition ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Stable id of one listening event — one closed playback span recorded by the
 * client and synced to the server. Listening events are append-only.
 */
@Serializable
@JvmInline
value class ListeningEventId(
    val value: String,
)

/**
 * Stable id of a user's materialized listening stats row. `value` equals the
 * owning `UserId.value` (1:1 with the user).
 */
@Serializable
@JvmInline
value class UserStatsId(
    val value: String,
)

/**
 * Type-safe wrapper for Unix epoch millisecond timestamps.
 *
 * Prevents accidentally comparing timestamps with durations or other numeric values.
 * Provides rich API for timestamp operations while compiling to primitive Long
 * with zero runtime overhead.
 *
 * @property epochMillis Unix epoch milliseconds
 */
@Serializable(with = TimestampIso8601Serializer::class)
@JvmInline
value class Timestamp(
    val epochMillis: Long,
) : Comparable<Timestamp> {
    override fun compareTo(other: Timestamp): Int = epochMillis.compareTo(other.epochMillis)

    /**
     * Calculate duration between two timestamps.
     */
    operator fun minus(other: Timestamp): Duration = (epochMillis - other.epochMillis).milliseconds

    /**
     * Add duration to timestamp.
     */
    operator fun plus(duration: Duration): Timestamp = Timestamp(epochMillis + duration.inWholeMilliseconds)

    override fun toString(): String = epochMillis.toString()

    /**
     * Convert to ISO 8601 date time string.
     * e.g. "2023-11-22T14:30:45.123Z"
     */
    fun toIsoString(): String = Instant.fromEpochMilliseconds(epochMillis).toString()

    companion object {
        /**
         * Get current system time as Timestamp.
         */
        fun now(): Timestamp = Timestamp(currentEpochMilliseconds())
    }
}

/**
 * Serializes [Timestamp] as an ISO-8601 string (e.g. "2024-11-20T14:30:45.123Z"),
 * identical to the wire form `kotlin.time.Instant` produces. This keeps the wire
 * contract stable for `@Serializable` types whose timestamp fields move from
 * `kotlin.time.Instant` to `Timestamp`.
 */
object TimestampIso8601Serializer : KSerializer<Timestamp> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.calypsan.listenup.core.Timestamp", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Timestamp,
    ) {
        encoder.encodeString(Instant.fromEpochMilliseconds(value.epochMillis).toString())
    }

    override fun deserialize(decoder: Decoder): Timestamp =
        Timestamp(Instant.parse(decoder.decodeString()).toEpochMilliseconds())
}
