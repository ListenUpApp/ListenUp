package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.web.features.admin.InboxBadgeState
import com.calypsan.listenup.web.features.admin.OpenInboxBadge
import com.calypsan.listenup.web.features.admin.OpenRestrictedBooks
import com.calypsan.listenup.web.features.library.LibrarySession
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.notifications.OpenNotificationBell
import com.calypsan.listenup.web.features.nowplaying.OpenPlayback
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.playback.bindMediaSession
import com.calypsan.listenup.web.playback.browserMediaSession

/**
 * Opens the playback session for as long as the shell is mounted, and closes it when it is not.
 *
 * `remember` with no key on purpose: this session is the shell's, not a route's, so a navigation
 * must not tear the player down mid-sentence.
 */
@Composable
internal fun playbackState(openPlayback: OpenPlayback): PlaybackSession {
    val session = rememberSession { openPlayback() }
    // Shell-scoped for the same reason the session is: what is playing outlives the page, and
    // an OS control bound to a route would stop working the moment the reader navigated.
    val bridge = remember { browserMediaSession() }
    val scope = rememberCoroutineScope()
    DisposableEffect(session, bridge) {
        val dispose = bridge?.let { bindMediaSession(session, it, scope) }
        onDispose { dispose?.invoke() }
    }
    return session
}

/**
 * The shell's unread count, open for as long as the app is.
 *
 * A `remember { }` with no key on purpose — unlike every per-entity session in this file, this one
 * is not keyed to anything that changes. Re-opening it on a route change would tear down and
 * rebuild the Room subscription behind the badge on every navigation.
 */
@Composable
internal fun notificationBadge(openNotificationBell: OpenNotificationBell): Int {
    val session = rememberSession { openNotificationBell() }
    return session.unreadCount.collectAsState().value
}

/**
 * The held count and cover preview, open for as long as the app is — keyed to nothing, for the
 * reason [notificationBadge] is: re-opening it per route would rebuild its Room subscription on
 * every navigation. Zero for anyone who is not an admin (the ViewModel's own gate).
 */
@Composable
internal fun inboxBadge(openInboxBadge: OpenInboxBadge): InboxBadgeState {
    val session = rememberSession { openInboxBadge() }
    return InboxBadgeState(
        heldCount = session.heldCount.collectAsState().value,
        previewBookIds = session.previewBookIds.collectAsState().value,
    )
}

/**
 * The restricted-book ids behind every card's lock, open for as long as the app is, for the reason
 * [inboxBadge] is. Empty for anyone who is not an admin (the repository's own gate).
 */
@Composable
internal fun restrictedBooks(openRestrictedBooks: OpenRestrictedBooks): Set<String> {
    val session = rememberSession { openRestrictedBooks() }
    return session.restrictedBookIds.collectAsState().value
}

/**
 * Opens a Library session while the page is showing and closes it when it stops, so the ViewModel's
 * flows do not outlive the route.
 */
@Composable
internal fun libraryState(openLibrary: OpenLibrary): LibrarySession {
    val session = rememberSession { openLibrary() }
    return session
}

/**
 * The library list, mirrored into local state.
 *
 * ⛔ **This used to wrap each change in a View Transition, and that has been removed** — not tuned,
 * removed. The browser's shared-element API needs the DOM change to happen inside its update
 * callback, and **Compose HTML cannot render in there**: the browser suppresses rendering while the
 * callback is outstanding, and Compose's scheduler needs a frame. Measured, the destination had
 * still not rendered **361 ms** into the callback, so every transition captured an identical before
 * and after — it animated nothing while holding the old page frozen for the settle, which is what
 * read as a lurch and a flash on every navigation.
 *
 * The cover's flight between the grid and the book survives, because it does not depend on that
 * callback at all — see `HeroFlight`.
 */
@Composable
internal fun animatedLibrary(session: LibrarySession): LibraryUiState {
    val upstream = session.state.collectAsState().value
    var shown by remember { mutableStateOf(upstream) }
    LaunchedEffect(upstream) { shown = upstream }
    return shown
}
