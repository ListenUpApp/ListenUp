package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.TagId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Read model for a tag exposed through [com.calypsan.listenup.api.TagService].
 *
 * Includes the [bookCount] computed via `LEFT JOIN COUNT(*)` on the `book_tags`
 * junction at query time — no denormalization. [slug] is the stable URL-safe
 * identity for deep-linking.
 */
@Serializable
@SerialName("TagSummary")
data class TagSummary(
    /** Stable identifier for this tag. */
    @SerialName("id") val id: TagId,
    /** URL-safe slug — immutable identity even after renames. */
    @SerialName("slug") val slug: String,
    /** Display name of the tag, e.g. "Sci-Fi". */
    @SerialName("name") val name: String,
    /** Number of books currently linked to this tag (live rows only, no tombstones). */
    @SerialName("bookCount") val bookCount: Long,
)

/**
 * A single tag match within [SearchResults].
 *
 * [bookCount] reflects the number of live books linked to this tag at search time.
 * Soft-deleted junction rows are excluded from the count.
 */
@Serializable
@SerialName("TagHit")
data class TagHit(
    /** Internal tag identifier. */
    @SerialName("id") val id: TagId,
    /** URL-safe slug — immutable identity for deep-linking. */
    @SerialName("slug") val slug: String,
    /** Display name of the tag, e.g. "Fantasy". */
    @SerialName("name") val name: String,
    /** Number of books currently linked to this tag in the library. */
    @SerialName("bookCount") val bookCount: Long,
    /**
     * The primary matched display field with matched query tokens wrapped in the
     * highlight marker U+0002 (STX) … U+0003 (ETX) sentinels the client splits on;
     * `null` when no token matched the displayed text.
     */
    @SerialName("highlight") val highlight: String? = null,
)
