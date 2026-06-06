package com.calypsan.listenup.client.domain.readers

/**
 * Readers state for one book.
 *
 * Assembled by [com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl] from two
 * sources: the current user's own reading state (from the local playback position — works offline)
 * and the other users currently listening (from the ACL-filtered, caller-excluded
 * `SocialService.bookReaders` RPC, refreshed on every presence ping). The current user, when they
 * are reading or have finished the book, is listed first.
 *
 * @property readers Everyone reading or who has finished this book that we can show — the current
 *   user (when applicable) followed by other live listeners.
 */
data class BookReaders(
    val readers: List<Reader>,
)

/**
 * A reader of a book, for display in the Readers section.
 *
 * Avatar resolution is intentionally omitted — the [UserAvatar] composable resolves avatars
 * internally from [userId], keeping this type dependency-free.
 *
 * @property userId Server-issued user identifier (used by [UserAvatar] for avatar lookup).
 * @property displayName Human-readable name shown beside the avatar.
 * @property state Whether the reader is actively listening or has finished the book.
 */
data class Reader(
    val userId: String,
    val displayName: String,
    val state: ReaderState,
)

/** A reader's relationship to the book — actively listening, or finished. */
sealed interface ReaderState {
    /** Actively listening to the book. */
    data object Listening : ReaderState

    /**
     * Finished the book.
     *
     * @property finishedAtMs When the book was finished (epoch ms), or null if unknown.
     */
    data class Finished(
        val finishedAtMs: Long?,
    ) : ReaderState
}
