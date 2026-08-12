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
