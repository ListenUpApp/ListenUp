package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.MoodId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Read model for a mood exposed through [com.calypsan.listenup.api.MoodService].
 *
 * Includes the [bookCount] computed via `COUNT(*)` on the `book_moods` junction at
 * query time — no denormalization. [slug] is the stable URL-safe identity for
 * deep-linking.
 */
@Serializable
@SerialName("MoodSummary")
data class MoodSummary(
    /** Stable identifier for this mood. */
    @SerialName("id") val id: MoodId,
    /** URL-safe slug — immutable identity even after renames. */
    @SerialName("slug") val slug: String,
    /** Display name of the mood, e.g. "Feel-Good". */
    @SerialName("name") val name: String,
    /** Number of books currently linked to this mood (live rows only, no tombstones). */
    @SerialName("bookCount") val bookCount: Long,
)
