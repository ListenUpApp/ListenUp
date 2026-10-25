package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.features.nowplaying.PlayerNavigation

/**
 * The player's navigation on this back stack: one set of destinations for the mini-player and Now Playing
 * (`NowPlayingHost`) and for the PDF reader's own player, so a player event lands in the same place from either.
 * Sign in and the inbox are the routes the lapsed-session banner and the admin Inbox entry use.
 */
internal fun MutableList<NavKey>.playerNavigation(): PlayerNavigation =
    PlayerNavigation(
        onNavigateToBook = { bookId -> add(BookDetail(bookId)) },
        onNavigateToDocument = { path, fromNowPlaying -> add(DocumentViewer(path, fromNowPlaying)) },
        onOpenSignIn = { if (lastOrNull() != Login) add(Login) },
        onOpenInbox = { add(AdminInbox) },
    )
