package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.ChapterId
import com.calypsan.listenup.client.core.ContributorId
import com.calypsan.listenup.client.core.SeriesId
import com.calypsan.listenup.client.core.Timestamp
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
fun BookResponse.toEntity(): BookEntity =
    BookEntity(
        id = BookId(id),
        title = title,
        sortTitle = sortTitle,
        subtitle = subtitle,
        coverHash = null, // TODO(T26): repopulate coverHash from the Books-A sync wire event
        coverBlurHash = coverImage?.blurHash,
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

fun ChapterResponse.toEntity(
    bookId: BookId,
    index: Int,
): com.calypsan.listenup.client.data.local.db.ChapterEntity =
    com.calypsan.listenup.client.data.local.db.ChapterEntity(
        // ID is composite of bookId and index since server doesn't provide stable chapter IDs yet
        id = ChapterId("${bookId.value}_$index"),
        bookId = bookId,
        title = title,
        duration = endTime - startTime,
        startTime = startTime,
    )

fun BookContributorResponse.toEntity(
    bookId: BookId,
    role: String,
): com.calypsan.listenup.client.data.local.db.BookContributorCrossRef =
    com.calypsan.listenup.client.data.local.db.BookContributorCrossRef(
        bookId = bookId,
        contributorId = ContributorId(contributorId),
        role = role,
        creditedAs = creditedAs,
    )

fun BookSeriesInfoResponse.toEntity(bookId: BookId): com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef =
    com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef(
        bookId = bookId,
        seriesId = SeriesId(seriesId),
        sequence = sequence,
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
fun String.toTimestamp(): Timestamp =
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
fun Timestamp.toIso8601(): String = Instant.fromEpochMilliseconds(this.epochMillis).toString()
