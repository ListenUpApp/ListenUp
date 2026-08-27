package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input row for [com.calypsan.listenup.api.BookService.setBookChapters].
 *
 * [id] is the stable chapter id. Clients preserve it for existing chapters and
 * mint a fresh ULID for newly-added ones, so identity survives reordering and
 * re-timing. [startTime] is the absolute offset from the start of the book in
 * milliseconds; [duration] is this chapter's length in milliseconds. Chapters
 * are contiguous — the server treats [startTime] as canonical and derives the
 * effective end from the next chapter's start (set-level invariants are checked
 * server-side in `setBookChapters`).
 */
@Serializable
@SerialName("ChapterInput")
data class ChapterInput(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("startTime") val startTime: Long,
    @SerialName("duration") val duration: Long,
    /**
     * The name of the Part this chapter OPENS, or null when it opens none — a header, not a
     * membership pointer. Blank is rejected rather than stored: the editor normalizes a cleared
     * field to null before sending, so a blank arriving here is a caller bug, and silently
     * accepting it would leave a group whose name renders as nothing.
     */
    @SerialName("partTitle") val partTitle: String? = null,
    /** The name of the Book this chapter OPENS, or null. May co-occur with [partTitle]. */
    @SerialName("bookTitle") val bookTitle: String? = null,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(title.length <= MAX_TITLE) { "title must be <= $MAX_TITLE chars" }
        require(startTime >= 0) { "startTime must be non-negative" }
        require(duration >= 0) { "duration must be non-negative" }
        require(partTitle == null || partTitle.isNotBlank()) { "partTitle must be null, not blank" }
        require(bookTitle == null || bookTitle.isNotBlank()) { "bookTitle must be null, not blank" }
        require(partTitle == null || partTitle.length <= MAX_SECTION_TITLE) {
            "partTitle must be <= $MAX_SECTION_TITLE chars"
        }
        require(bookTitle == null || bookTitle.length <= MAX_SECTION_TITLE) {
            "bookTitle must be <= $MAX_SECTION_TITLE chars"
        }
    }

    companion object {
        const val MAX_TITLE = 1024

        /** Ceiling for [partTitle]/[bookTitle]. A section name is a heading, not a chapter title. */
        const val MAX_SECTION_TITLE = 256
    }
}
