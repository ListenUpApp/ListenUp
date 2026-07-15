package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookEntity
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * Extension functions for mapping DTOs from server to local database entities.
 *
 * Converts raw types from JSON (String IDs, String timestamps) into
 * type-safe value classes (BookId, Timestamp) for compile-time safety.
 */

/**
 * Convert BookResponse from server to BookEntity for local storage.
 *
 * Maps raw String ID to type-safe BookId and ISO 8601 timestamps to
 * type-safe Timestamp value classes.
 *
 * @receiver BookResponse from API
 * @return BookEntity ready for Room insertion
 */
internal fun BookResponse.toEntity(): BookEntity =
    BookEntity(
        id = BookId(id),
        // The legacy REST BookResponse predates the Library domain — libraryId/folderId are not on the wire.
        // Use sentinel placeholders; this path is superseded by the BookService RPC (Books-C).
        libraryId = LibraryId("unknown"),
        folderId = FolderId("unknown"),
        title = title,
        sortTitle = sortTitle,
        subtitle = subtitle,
        // coverHash intentionally null here: the legacy BookResponse path is superseded by the
        // BookService RPC fetch.
        coverHash = null,
        totalDuration = totalDuration,
        description = description,
        // Series is now stored via book_series junction table (many-to-many)
        publishYear = publishYear?.toIntOrNull(),
        publisher = publisher,
        language = language,
        isbn = isbn,
        asin = asin,
        abridged = abridged,
        // Timestamps from server
        createdAt = createdAt.toTimestamp(),
        updatedAt = updatedAt.toTimestamp(),
    )

/**
 * Parse ISO 8601 timestamp string to type-safe Timestamp.
 *
 * Converts server timestamp format (RFC 3339) to our type-safe Timestamp value class.
 * Provides detailed error messages for debugging malformed timestamps.
 *
 * @receiver ISO 8601 timestamp string (e.g., "2025-11-22T14:30:45Z")
 * @return Type-safe Timestamp value class
 * @throws IllegalArgumentException if timestamp format is invalid, with details about the parsing failure
 */
@OptIn(ExperimentalTime::class)
internal fun String.toTimestamp(): Timestamp =
    try {
        Timestamp(Instant.parse(this).toEpochMilliseconds())
    } catch (e: IllegalArgumentException) {
        // Provide context for debugging - which timestamp failed and why
        throw IllegalArgumentException(
            "Failed to parse timestamp '$this'. Expected ISO 8601 format (e.g., '2025-11-22T14:30:45Z'). " +
                "Original error: ${e.message}",
            e,
        )
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Catch any other parsing errors
        throw IllegalArgumentException(
            "Unexpected error parsing timestamp '$this': ${e.message}",
            e,
        )
    }

/**
 * Convert type-safe Timestamp to ISO 8601 string for API requests.
 *
 * Converts our type-safe Timestamp value class to server timestamp format for API requests.
 *
 * @receiver Type-safe Timestamp
 * @return ISO 8601 timestamp string (e.g., "2025-11-22T14:30:45Z")
 */
@OptIn(ExperimentalTime::class)
internal fun Timestamp.toIso8601(): String = Instant.fromEpochMilliseconds(this.epochMillis).toString()
