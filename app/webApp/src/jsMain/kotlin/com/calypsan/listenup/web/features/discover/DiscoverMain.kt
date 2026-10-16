package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.web.design.AppLink
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.books.press
import com.calypsan.listenup.web.features.shelf.bookCountLabel
import com.calypsan.listenup.web.nav.Routes
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

/*
 * Discover's main column: who is listening, something new, other people's shelves, and what was
 * recently added: the books and the people around them.
 */

/**
 * "Listening now", the section that makes this a shared library rather than a private one, which is
 * why it leads. A count line says how many are live and how many more in the last two weeks.
 *
 * Two kinds of card in one list: anyone with a live session, wearing "Live" on the art, then everyone
 * else on the book they last played. [CurrentlyListeningUiSession.isLive] says which.
 */
@Composable
internal fun ListeningNowSection(
    state: CurrentlyListeningUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    selection: BookSelection?,
    onRefresh: () -> Unit,
) {
    val ready = state as? CurrentlyListeningUiState.Ready
    Section(
        DiscoverWords.LISTENING_NOW,
        subtitle = ready?.let { DiscoverWords.listeningCount(it.liveCount, it.recentCount) },
        styleClass = "disc-a-listen",
    ) {
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

/**
 * A listener: the art and title are one link to the book, named for the book and the person; the
 * person row under it is a sibling link to their profile. Never one link inside another.
 */
@Composable
internal fun ListenerCard(
    session: CurrentlyListeningUiSession,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    selection: BookSelection?,
) {
    val whenText = relativeLastActive(session.lastActiveAt, nowMs)
    Div(attrs = {
        classes("disc-listener")
        if (selection?.isSelected(session.bookId) == true) classes("is-sel")
    }) {
        AppLink(
            href = Routes.Book.detail(session.bookId).toUrl(),
            onOpen = { selection.press(session.bookId) { onOpenBook(session.bookId) } },
            attrs = {
                classes("disc-listener-book")
                attr("aria-label", DiscoverWords.listenerLabel(session.bookTitle, session.displayName, session.isLive, whenText))
            },
        ) {
            Cover(
                title = session.bookTitle,
                imageUrl = coverUrl(session.bookId, session.coverHash, width = CARD_COVER_WIDTH),
                size = null,
                decorative = true,
                overlay = {
                    RestrictedMarker(session.bookId)
                    if (session.isLive) Span(attrs = { classes("disc-live-chip") }) { Text(DiscoverWords.LIVE) }
                },
            )
            Span(attrs = { classes("disc-card-t") }) { Text(session.bookTitle) }
        }
        AppLink(
            href = Routes.profile(session.userId).toUrl(),
            onOpen = { onOpenProfile(session.userId) },
            attrs = { classes("disc-person") },
        ) {
            UserAvatar(userId = session.userId, name = session.displayName, size = LISTENER_AVATAR)
            Span(attrs = { classes("disc-person-text") }) {
                Span(attrs = { classes("disc-person-name") }) { Text(session.displayName) }
                Span(attrs = { classes(if (session.isLive) "disc-live" else "disc-when") }) {
                    Text(if (session.isLive) DiscoverWords.LISTENING_NOW else whenText)
                }
            }
        }
    }
}

/**
 * "Something new": books the reader has never started, first in their series, so the shelf keeps
 * offering a way in. Each pick says which book of its series it is and how long it runs; Shuffle
 * draws ten different books.
 */
@Composable
internal fun SomethingNewSection(
    state: DiscoverBooksUiState,
    onOpenBook: (String) -> Unit,
    onShuffle: () -> Unit,
    selection: BookSelection?,
    onRefresh: () -> Unit,
) {
    Section(
        DiscoverWords.SOMETHING_NEW,
        subtitle = DiscoverWords.SOMETHING_NEW_SUBTITLE,
        styleClass = "disc-a-new",
        actions = {
            Button(
                kind = ButtonKind.Secondary,
                size = ButtonSize.Sm,
                onClick = onShuffle,
                label = DiscoverWords.SHUFFLE_A11Y,
            ) {
                Icon(WebIcon.Shuffle, size = SHUFFLE_ICON)
                Text(DiscoverWords.SHUFFLE)
            }
        },
    ) {
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
                    Div(attrs = { classes("disc-picks") }) {
                        state.books.forEach { book ->
                            key(book.id) { PickCard(book, onOpenBook, selection) }
                        }
                    }
                }
            }
        }
    }
}

/** A pick: one link to the book, its cover, title, author, then "Book 1 of Mistborn · 24h 39m". */
@Composable
internal fun PickCard(
    book: DiscoverUiBook,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    AppLink(
        href = Routes.Book.detail(book.id).toUrl(),
        onOpen = { selection.press(book.id) { onOpenBook(book.id) } },
        attrs = {
            classes("disc-card")
            if (selection?.isSelected(book.id) == true) classes("is-sel")
        },
    ) {
        Cover(
            title = book.title,
            imageUrl = coverUrl(book.id, book.coverHash, width = CARD_COVER_WIDTH),
            size = null,
            decorative = true,
            overlay = { RestrictedMarker(book.id) },
        )
        Span(attrs = { classes("disc-card-t") }) { Text(book.title) }
        book.authorName?.let { Span(attrs = { classes("disc-card-sub") }) { Text(it) } }
        Span(attrs = { classes("disc-pick-meta") }) {
            Text(
                "${DiscoverWords.seriesLine(book.seriesName, book.sequenceLabel)} · " +
                    DurationFormatter.hoursMinutes(book.durationMs.milliseconds),
            )
        }
    }
}

/** The Shuffle glyph, in px. */
private const val SHUFFLE_ICON = 16

/** "Recently added" — what the library gained while the reader was away. */
@Composable
internal fun RecentlyAddedSection(
    state: RecentlyAddedUiState,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
    onRefresh: () -> Unit,
) {
    Section("Recently added", styleClass = "disc-a-recent") {
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
    Section("Shelves from others", styleClass = "disc-a-shelves") {
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
