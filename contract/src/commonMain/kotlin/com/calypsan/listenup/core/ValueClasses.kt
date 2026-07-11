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
 * A library groups N root folders under a single named collection. Every book
 * belongs to exactly one library. Wrapping the id prevents it being confused
 * with a [BookId], [FolderId], or any other string id at call sites that thread
 * both — notably the scanner's `resolveOrInsert(libraryId, analyzed)`.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying library ID string (UUIDv7 at the storage layer).
 */
@Serializable
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
 * Type-safe wrapper for LibraryFolder IDs.
 *
 * A library folder is one of N root paths registered under a [LibraryId].
 * The scanner walks each folder independently and aggregates results into the
 * parent library's book list. Wrapping the id prevents it being confused with
 * [LibraryId] or [BookId] at call sites that manage both folder and library
 * lifecycles — notably [WatcherSupervisor] and [ScanOrchestrator].
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying folder ID string (UUIDv7 at the storage layer).
 */
@Serializable
@JvmInline
value class FolderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Folder ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Chapter IDs.
 */
@Serializable
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
@Serializable
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
@Serializable
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
 * Type-safe wrapper for Tag IDs.
 *
 * Tags are server-wide (global, not user-scoped). Wrapping the id prevents
 * accidentally passing a [BookId] or any other string id where a tag id is
 * expected — particularly at [com.calypsan.listenup.api.TagService] call sites
 * that thread both.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying tag ID string (UUIDv7 at the storage layer).
 */
@Serializable
@JvmInline
value class TagId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Tag ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Mood IDs.
 *
 * Moods are global (cross-user, single server) — the affective axis of a book
 * ("Feel-Good", "Tense", "Scary"), independent of genre and tag. Wrapping the id
 * prevents accidentally passing a [BookId], [TagId], [GenreId], or any other string
 * id where a mood id is expected — particularly at `MoodService` call sites that
 * thread mood, book, and user identifiers through curation operations.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying mood ID string (UUIDv7 at the storage layer).
 */
@Serializable
@JvmInline
value class MoodId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Mood ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Collection IDs.
 *
 * Collections are user-scoped: each user owns their own inbox and any manually
 * created collections, and may have read or write access to collections shared by
 * others. Wrapping the id prevents accidentally passing a [BookId], [TagId], or any
 * other string id where a collection id is expected — particularly at
 * `CollectionService` call sites that thread collection, book, and user identifiers
 * through ownership and sharing operations.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying collection ID string (UUIDv7 at the storage layer).
 */
@Serializable
@JvmInline
value class CollectionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Collection ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Shelf IDs.
 *
 * Shelves are user-owned curated lists of books for personal organization and
 * social discovery. Each user may create multiple shelves; every shelf is scoped
 * to the owning user. Wrapping the id prevents accidentally passing a [BookId],
 * [CollectionId], or any other string id where a shelf id is expected —
 * particularly at `ShelfService` call sites that thread shelf, book, and user
 * identifiers through ownership and discovery operations.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying shelf ID string (UUIDv7 at the storage layer).
 */
@Serializable
@JvmInline
value class ShelfId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Shelf ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for reading-order IDs.
 *
 * Reading orders are user-owned, named, ordered, attributed lists of books — the
 * personal-artifact tier of the Story World epic (spec §5.1), architecturally a
 * near-exact sibling of [ShelfId]. Wrapping the id prevents accidentally passing a
 * [BookId] or any other string id where a reading-order id is expected.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying reading-order ID string (UUIDv7 at the storage layer).
 */
@Serializable
@JvmInline
value class ReadingOrderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ReadingOrderId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for backup archive IDs.
 *
 * A backup id is the archive's stable filename stem (e.g. `backup-2026-06-02T18-30-00Z`),
 * since backups are filesystem-truth rather than database rows.
 */
@Serializable
@JvmInline
value class BackupId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Backup ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for ABS import job IDs.
 *
 * Import IDs are server-minted stable identifiers for staged ABS import jobs
 * (e.g. `abs-550e8400-e29b-41d4-a716-446655440000`). Jobs are filesystem-truth
 * under `$LISTENUP_HOME/imports/<id>/`, so the ID is the directory name.
 */
@Serializable
@JvmInline
value class ImportId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Import ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Audiobookshelf user IDs.
 *
 * ABS user IDs are opaque strings from the ABS SQLite database. Wrapping them
 * prevents accidentally passing a ListenUp [UserId][com.calypsan.listenup.api.dto.auth.UserId]
 * where an ABS user ID is expected during user-mapping operations.
 */
@Serializable
@JvmInline
value class AbsUserId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ABS user ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Audiobookshelf library item IDs.
 *
 * ABS item IDs are opaque strings identifying the ABS media item — the ABS SQLite `books.id`
 * value that `AbsBackupReader` correlates progress and matches against. Wrapping them prevents
 * accidentally passing a ListenUp [BookId] where an ABS item ID is expected during book-matching
 * and progress-apply operations.
 */
@Serializable
@JvmInline
value class AbsItemId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ABS item ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Genre IDs.
 *
 * Genres are server-wide (global, not user-scoped) and form a hierarchy via
 * materialized path. Wrapping the id prevents accidentally passing a [BookId],
 * [TagId], or any other string id where a genre id is expected — particularly
 * at [com.calypsan.listenup.api.GenreService] call sites that thread parent /
 * source / target identifiers through hierarchy and merge operations.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying genre ID string (UUIDv7 at the storage layer).
 */
@Serializable
@JvmInline
value class GenreId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Genre ID cannot be blank" }
    }

    override fun toString(): String = value
}

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
