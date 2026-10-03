package com.calypsan.listenup.client.domain.readers

import com.calypsan.listenup.client.domain.model.ListenerRating

/**
 * Readers state for one book.
 *
 * Assembled by [com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl] from the
 * ACL-filtered `SocialService.bookReadership` RPC — which now *includes* the caller — refreshed on
 * every presence ping. Each entry carries both states at once: live progress and finish history.
 *
 * @property readers Everyone reading or who has finished this book that we can show, including the
 *   current user when applicable.
 */
data class BookReaders(
    val readers: List<Reader>,
)

/**
 * A reader of a book, for the Readers section. Carries both states at once: [currentProgressPct]
 * (non-null ⇒ reading now) and [finishes] (dated completions, newest-first; may be empty).
 *
 * Avatar resolution is omitted — the UI's UserAvatar resolves avatars from [userId].
 *
 * @property userId Server-issued user identifier (used by the UI for avatar lookup).
 * @property displayName Human-readable name shown beside the avatar.
 * @property isYou Whether this reader is the current user.
 * @property currentProgressPct 0..100 when the user has an in-progress (unfinished) position; null
 *   otherwise. Non-null ⇒ reading now.
 * @property finishes Dated completions (epoch ms), newest-first; may be empty.
 * @property rating This reader's rating of the book, when they left one.
 * @property hardcoverFinishes Reads logged on Hardcover and pulled (#601 B3), epoch ms newest-first.
 *   Apart from [finishes] so every surface can badge them; they never count as listening.
 * @property finishesAlsoOnHardcover The [finishes] that were also logged on Hardcover — the server
 *   paired each with a Hardcover read of the same listen, so it is one row that says "Also on
 *   Hardcover", never a second Hardcover row. A subset of [finishes], epoch ms newest-first.
 */
data class Reader(
    val userId: String,
    val displayName: String,
    val isYou: Boolean,
    val currentProgressPct: Int?,
    val finishes: List<Long>,
    val rating: ListenerRating? = null,
    val hardcoverFinishes: List<Long> = emptyList(),
    val finishesAlsoOnHardcover: List<Long> = emptyList(),
)
