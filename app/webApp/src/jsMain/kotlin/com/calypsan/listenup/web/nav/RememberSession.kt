package com.calypsan.listenup.web.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** A page's session: the state it reads, the actions it sends, and how to let go of it. */
interface RouteSession {
    /** Releases everything the session opened; called once, when the route leaves or its key changes. */
    val close: () -> Unit
}

/**
 * Opens a session for the current route and closes it when the route leaves or a [keys] value
 * changes.
 *
 * Pass as a key whatever identifies the page's subject — the book id, the collection id, the
 * search query and type. A route whose subject can change while it stays mounted (one tag page to
 * another, one book to the next) must be keyed on that subject: without the key the old session
 * keeps showing the previous subject's data. A route with no subject passes no key.
 */
@Composable
fun <S : RouteSession> rememberSession(
    vararg keys: Any?,
    open: () -> S,
): S {
    val session = remember(*keys) { open() }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}
