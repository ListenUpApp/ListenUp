package com.calypsan.listenup.client.domain.readers

/**
 * Readers state for one book.
 *
 * [currentlyListening] is populated from the `SocialService.bookReaders` RPC, which is
 * ACL-filtered and already excludes the current user server-side. The list refreshes on
 * subscribe and on every presence ping (see
 * [com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl]).
 *
 * @property currentlyListening Users currently listening to this book (others only).
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
