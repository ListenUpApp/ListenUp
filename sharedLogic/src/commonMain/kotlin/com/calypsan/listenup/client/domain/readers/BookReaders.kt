package com.calypsan.listenup.client.domain.readers

/**
 * Readers state for one book, partitioned by engagement type.
 *
 * [currentlyListening] is populated from the `active_sessions` Room table, which is kept
 * current by SSE events. Rows are deleted from `active_sessions` when the server records
 * a book-completion flip (the P3-B cascade), so a user moves out of [currentlyListening]
 * automatically when they finish.
 *
 * [completedBy] is populated from the current user's `playback_positions` row for the
 * book when [PlaybackPositionEntity.isFinished] is true. The `playback_positions` table
 * stores only the current user's position (one row per book, no userId column), so this
 * list contains at most one entry — the current user, when they have finished the book.
 *
 * Both lists exclude the authenticated user via [currentUserId] filtering in
 * [com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl], except
 * [completedBy] which uses the current user's position to show their own completion.
 *
 * @property currentlyListening Users currently listening to this book (active sessions, others only).
 * @property completedBy Users who have completed this book (current user only if finished).
 * @property totalCompletions Count of completed entries in [completedBy].
 */
data class BookReaders(
    val currentlyListening: List<Reader>,
    val completedBy: List<Reader>,
    val totalCompletions: Int,
)

/**
 * A minimal reader identity for display in the Readers section.
 *
 * Avatar resolution is intentionally omitted — the [UserAvatar] composable
 * resolves avatars internally from [userId], keeping this type dependency-free.
 *
 * @property userId Server-issued user identifier (used by [UserAvatar] for avatar lookup).
 * @property displayName Human-readable name shown beneath the avatar.
 */
data class Reader(
    val userId: String,
    val displayName: String,
)
