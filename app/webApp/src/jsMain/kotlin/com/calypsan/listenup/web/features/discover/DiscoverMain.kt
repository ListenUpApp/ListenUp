package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.books.press
import com.calypsan.listenup.web.features.shelf.bookCountLabel
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/*
 * Discover's main column: who is listening, something new, other people's shelves, and what was
 * recently added: the books and the people around them.
 */

/**
 * "What others are listening to" — the section that makes this a shared library rather than a
 * private one, which is why it leads.
 *
 * Two kinds of row in one list: anyone with a live session, marked "Listening now", then everyone
 * else on the book they last played. [CurrentlyListeningUiSession.isLive] says which.
 */
@Composable
internal fun CurrentlyListeningSection(
    state: CurrentlyListeningUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    selection: BookSelection?,
    onRefresh: () -> Unit,
) {
    Section("What others are listening to") {
        when (state) {
            is CurrentlyListeningUiState.Loading -> {
                SectionSkeleton()
            }

            is CurrentlyListeningUiState.Error -> {
                SectionError(state.error.message, onRetry = onRefresh)
            }

            is CurrentlyListeningUiState.Ready -> {
                if (state.isEmpty) {
                    EmptyState(title = "Nobody else is listening yet", body = "When they do, you will see them here.")
                } else {
                    Div(attrs = { classes("disc-listeners") }) {
                        state.sessions.forEach { s ->
                            key(s.sessionId) {
                                ListenerCard(s, nowMs, onOpenBook, onOpenProfile, selection)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ListenerCard(
    session: CurrentlyListeningUiSession,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    selection: BookSelection?,
) {
    Button(attrs = {
        classes("disc-listener")
        if (selection?.isSelected(session.bookId) == true) classes("is-sel")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-label", "${session.displayName} — ${session.bookTitle}")
        onClick { selection.press(session.bookId) { onOpenBook(session.bookId) } }
    }) {
        Cover(
            title = session.bookTitle,
            imageUrl = coverUrl(session.bookId, session.coverHash, width = LISTENER_COVER_WIDTH),
            size = LISTENER_COVER_WIDTH,
            decorative = true,
            overlay = { RestrictedMarker(session.bookId, compact = true) },
        )
        Div(attrs = { classes("disc-listener-text") }) {
            // ⛔ The name, not the card: the card opens the book, and a second click target inside
            // a button is not one — so the name is a sibling of the card, rendered as a link.
            PersonLink(session.displayName, session.userId, onOpenProfile, "disc-listener-who")
            Span(attrs = { classes("disc-listener-book") }) { Text(session.bookTitle) }
            Span(attrs = {
                // The live marker is the one thing on this page that changes while you watch it.
                classes(if (session.isLive) "disc-live" else "disc-when")
            }) {
                Text(if (session.isLive) "Listening now" else relativeLastActive(session.lastActiveAt, nowMs))
            }
        }
    }
}

/** "Something new" — books the reader has never started, so the shelf keeps offering a way in. */
@Composable
internal fun DiscoverBooksSection(
    state: DiscoverBooksUiState,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
    onRefresh: () -> Unit,
) {
    Section("Something new") {
        when (state) {
            is DiscoverBooksUiState.Loading -> {
                SectionSkeleton()
            }

            is DiscoverBooksUiState.Error -> {
                SectionError(state.error.message, onRetry = onRefresh)
            }

            is DiscoverBooksUiState.Ready -> {
                if (state.isEmpty) {
                    EmptyState(title = "Nothing left to discover", body = "You have started everything in the library.")
                } else {
                    Div(attrs = { classes("disc-grid") }) {
                        state.books.forEach { book ->
                            key(book.id) {
                                BookCard(book.id, book.title, book.authorName, book.coverHash, onOpenBook, selection)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Recently added" — what the library gained while the reader was away. */
@Composable
internal fun RecentlyAddedSection(
    state: RecentlyAddedUiState,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
    onRefresh: () -> Unit,
) {
    Section("Recently added") {
        when (state) {
            is RecentlyAddedUiState.Loading -> {
                SectionSkeleton()
            }

            is RecentlyAddedUiState.Error -> {
                SectionError(state.error.message, onRetry = onRefresh)
            }

            is RecentlyAddedUiState.Ready -> {
                if (state.isEmpty) {
                    EmptyState(title = "Nothing new yet", body = "Books appear here as they are added to the library.")
                } else {
                    Div(attrs = { classes("disc-grid") }) {
                        state.books.forEach { book ->
                            key(book.id) {
                                BookCard(book.id, book.title, book.authorName, book.coverHash, onOpenBook, selection)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookCard(
    bookId: String,
    title: String,
    authorName: String?,
    coverHash: String?,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    Button(attrs = {
        classes("disc-card")
        if (selection?.isSelected(bookId) == true) classes("is-sel")
        attr(ATTR_TYPE, VALUE_BUTTON)
        onClick { selection.press(bookId) { onOpenBook(bookId) } }
    }) {
        Cover(
            title = title,
            imageUrl = coverUrl(bookId, coverHash, width = CARD_COVER_WIDTH),
            size = CARD_COVER_WIDTH,
            decorative = true,
            overlay = { RestrictedMarker(bookId) },
        )
        Span(attrs = { classes("disc-card-t") }) { Text(title) }
        authorName?.let { Span(attrs = { classes("disc-card-sub") }) { Text(it) } }
    }
}

/**
 * Other people's shelves, grouped under the person who made them.
 *
 * Only public shelves reach here — the server filters, so this renders whatever it is given without
 * a privacy branch of its own. A client-side filter would be a second opinion about a question the
 * server has already answered, and the wrong place to be wrong.
 */
@Composable
internal fun SharedShelvesSection(
    state: DiscoverShelvesUiState,
    onOpenShelf: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Section("Shelves from others") {
        when (state) {
            is DiscoverShelvesUiState.Loading -> {
                SectionSkeleton()
            }

            is DiscoverShelvesUiState.Error -> {
                SectionError(state.error.message, onRetry = onRefresh)
            }

            is DiscoverShelvesUiState.Ready -> {
                if (state.isEmpty) {
                    EmptyState(title = "No shared shelves yet", body = "Shelves other people make public show up here.")
                } else {
                    state.users.forEach { owner ->
                        key(owner.user.id) {
                            Div(attrs = { classes("disc-shelf-owner") }) {
                                PersonLink(
                                    owner.user.displayName,
                                    owner.user.id,
                                    onOpenProfile,
                                    "disc-shelf-who",
                                )
                                Div(attrs = { classes("disc-shelves") }) {
                                    owner.shelves.forEach { shelf ->
                                        key(shelf.id) {
                                            Button(attrs = {
                                                classes("disc-shelf")
                                                attr(ATTR_TYPE, VALUE_BUTTON)
                                                onClick { onOpenShelf(shelf.id) }
                                            }) {
                                                Span(attrs = { classes("disc-shelf-t") }) { Text(shelf.name) }
                                                Span(attrs = { classes("disc-shelf-sub") }) {
                                                    Text(bookCountLabel(shelf.bookCount))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
