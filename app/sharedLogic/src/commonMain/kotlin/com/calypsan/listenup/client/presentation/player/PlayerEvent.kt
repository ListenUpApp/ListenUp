package com.calypsan.listenup.client.presentation.player

/** One-shot navigation the platform performs (spec §4.2). Delivered through a buffered channel. */
sealed interface PlayerEvent {
    /** Open the in-app reader on a downloaded document. */
    data class OpenDocumentViewer(
        val localPath: String,
    ) : PlayerEvent

    /** Go to sign-in (the SessionExpired primary). */
    data object OpenSignIn : PlayerEvent

    /** Go to the Inbox (the held primary, admins). */
    data object OpenInbox : PlayerEvent

    /** Go to a book's detail (Go to book, Play next book's navigation). */
    data class NavigateToBook(
        val bookId: String,
    ) : PlayerEvent
}
