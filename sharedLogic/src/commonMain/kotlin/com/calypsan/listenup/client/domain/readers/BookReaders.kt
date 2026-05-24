package com.calypsan.listenup.client.domain.readers

/**
 * Readers state for one book.
 *
 * [currentlyListening] is populated from the `active_sessions` Room table, which is kept
 * current by SSE events. Rows are deleted from `active_sessions` when the server records
 * a book-completion flip (the P3-B cascade), so a user moves out of [currentlyListening]
 * automatically when they finish.
 *
 * The current user is excluded from the list via [currentUserId] filtering in
 * [com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl].
 *
 * @property currentlyListening Users currently listening to this book (active sessions, others only).
 */
data class BookReaders(
    val currentlyListening: List<Reader>,
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
