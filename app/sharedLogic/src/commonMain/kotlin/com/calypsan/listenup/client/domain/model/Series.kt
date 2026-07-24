package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp

/**
 * Domain model representing a book series.
 *
 * A series is a collection of related books, typically with a shared narrative
 * or thematic connection. Examples: "The Lord of the Rings", "Harry Potter",
 * "The Stormlight Archive".
 *
 * Books can belong to multiple series (e.g., "Mistborn: The Final Empire" is in
 * both "Mistborn" and "The Cosmere"). This model represents the series itself;
 * the book-series relationship with sequence numbers is managed separately.
 */
data class Series(
    val id: SeriesId,
    val name: String,
    val description: String? = null,
    val createdAt: Timestamp = Timestamp(0),
    /** Server-relative path to the series cover image. Null when no cover has been applied. */
    val coverPath: String? = null,
    /** Audible ASIN for this series, set when metadata has been applied. */
    val asin: String? = null,
) {
    /** The series id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value
}
