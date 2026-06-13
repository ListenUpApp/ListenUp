package com.calypsan.listenup.client.domain.model

/**
 * Hydrated projection of a single inbox book for the admin review-and-release queue.
 *
 * The admin inbox lists freshly-ingested books awaiting triage. The authoritative id set
 * comes from the inbox REST surface; display detail is joined from Room so the queue shows a
 * real cover, title, author, and duration rather than a raw book id.
 *
 * @property id The book's id (the selection key and the value passed to the release call).
 * @property title The book's display title.
 * @property author The primary author display name, or `null` when the book has no author credit.
 * @property coverPath Local cover file path when the cover exists on disk, else `null`.
 * @property coverHash Content hash of the current cover, used to bust the image cache on re-cover.
 * @property durationMs Total audiobook duration in milliseconds.
 */
data class InboxBookItem(
    val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val durationMs: Long,
    val coverHash: String? = null,
)
