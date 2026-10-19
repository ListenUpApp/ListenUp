package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.client.util.relativeLastActiveInSentence
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.web.design.AppLink
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.design.ShelfMosaic
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.books.SelectableTile
import com.calypsan.listenup.web.features.books.press
import com.calypsan.listenup.web.features.shelf.bookCountLabel
import com.calypsan.listenup.web.nav.Routes
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
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
    onRetry: () -> Unit,
    community: CommunitySize,
    viewer: DiscoverViewer?,
    onInvite: (() -> Unit)?,
) {
    val ready = state as? CurrentlyListeningUiState.Ready
    Section(
        DiscoverWords.LISTENING_NOW,
        subtitle = ready?.let { DiscoverWords.listeningCount(it.liveCount, it.recentCount) },
        styleClass = "disc-a-listen",
    ) {
        when (state) {
            is CurrentlyListeningUiState.Loading -> {
                ListenersSkeleton()
            }

            is CurrentlyListeningUiState.Error -> {
                SectionError(
                    if (state.needsConnection) DiscoverWords.LISTENING_NEED_CONNECTION else DiscoverWords.ERROR_LISTENING,
                    onRetry = onRetry,
                )
            }

            is CurrentlyListeningUiState.Ready -> {
                if (state.isEmpty) {
                    when (community) {
                        // Never claim "nobody" or "just you" before the roster has synced (plan C4).
                        CommunitySize.Unknown -> {
                            ListenersSkeleton()
                        }

                        CommunitySize.JustYou -> {
                            OnlyYouInvitation(
                                viewer,
                                onInvite.takeIf { viewer?.canInvite == true },
                            )
                        }

                        CommunitySize.Others -> {
                            QuietLine(DiscoverWords.NOBODY_LISTENING)
                        }
                    }
                } else {
                    val staleSince = state.observedAtMs?.takeIf { state.isStale }
                    staleSince?.let { observedAt ->
                        P(attrs = {
                            classes("disc-as-of")
                            attr("role", "status")
                        }) { Text(DiscoverWords.listeningAsOf(relativeLastActiveInSentence(observedAt, nowMs))) }
                    }
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

/** Listening now while loading: listener cards (art, title, then the person row) in the listeners' rail. */
@Composable
private fun ListenersSkeleton() {
    CardSkeletons("disc-listeners", captionLines = 2, cardClass = "disc-listener")
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
    // A pick is marked on the tile (its check); an outline on this card was clipped by the rail.
    Div(attrs = { classes("disc-listener") }) {
        SelectableTile(
            bookId = session.bookId,
            selection = selection,
            label = DiscoverWords.listenerLabel(session.bookTitle, session.displayName, session.isLive, whenText),
            href = Routes.Book.detail(session.bookId).toUrl(),
            onOpen = { selection.press(session.bookId) { onOpenBook(session.bookId) } },
            classes = listOf("disc-listener-book"),
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
    onRetry: () -> Unit,
    onBrowseLibrary: (() -> Unit)?,
) {
    // Shuffle draws again; with nothing to draw (all started) or a failure (Try again is the action) it steps aside.
    val canShuffle = state is DiscoverBooksUiState.Ready || state is DiscoverBooksUiState.Loading
    // Select starts a selection once there is something to select; while selecting the header says so,
    // and the selection bar's Cancel ends it (spec §3.5, X35).
    val canSelect = selection != null && state is DiscoverBooksUiState.Ready && !state.isEmpty
    val actions: @Composable () -> Unit = {
        if (canSelect) SelectControl(selection)
        if (canShuffle) ShuffleButton(onShuffle)
    }
    Section(
        DiscoverWords.SOMETHING_NEW,
        subtitle = DiscoverWords.SOMETHING_NEW_SUBTITLE,
        styleClass = "disc-a-new",
        actions = actions.takeIf { canShuffle || canSelect },
    ) {
        SomethingNewBody(state, onOpenBook, selection, onRetry, onBrowseLibrary)
    }
}

/** Select, or the word "Selecting" while a selection is under way. */
@Composable
private fun SelectControl(selection: BookSelection) {
    if (selection.isSelecting) {
        Span(attrs = { classes("disc-selecting") }) { Text(DiscoverWords.SELECTING) }
    } else {
        Button(
            kind = ButtonKind.Secondary,
            size = ButtonSize.Sm,
            onClick = selection.onStart,
            attrs = { classes("disc-select") },
        ) { Text(DiscoverWords.SELECT) }
    }
}

/** Shuffle: draws ten different picks. */
@Composable
private fun ShuffleButton(onShuffle: () -> Unit) {
    Button(
        kind = ButtonKind.Secondary,
        size = ButtonSize.Sm,
        onClick = onShuffle,
        label = DiscoverWords.SHUFFLE_A11Y,
    ) {
        Icon(WebIcon.Shuffle, size = SHUFFLE_ICON)
        Text(DiscoverWords.SHUFFLE)
    }
}

/** Something new under its heading, by state (spec §3.8). */
@Composable
private fun SomethingNewBody(
    state: DiscoverBooksUiState,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
    onRetry: () -> Unit,
    onBrowseLibrary: (() -> Unit)?,
) {
    when (state) {
        is DiscoverBooksUiState.Loading -> {
            CardSkeletons("disc-picks", captionLines = 3)
        }

        is DiscoverBooksUiState.Error -> {
            SectionError(DiscoverWords.ERROR_PICKS, onRetry = onRetry)
        }

        is DiscoverBooksUiState.AllStarted -> {
            QuietLine(DiscoverWords.STARTED_EVERYTHING)
            onBrowseLibrary?.let { browse ->
                Button(kind = ButtonKind.Secondary, onClick = browse, attrs = { classes("disc-browse") }) {
                    Text(DiscoverWords.BROWSE_THE_LIBRARY)
                }
            }
        }

        is DiscoverBooksUiState.Ready -> {
            if (state.isEmpty) {
                QuietLine(DiscoverWords.NO_NEW_BOOKS_YET)
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

/** A pick: one link to the book, its cover, title, author, then "Book 1 of Mistborn · 24h 39m". */
@Composable
internal fun PickCard(
    book: DiscoverUiBook,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    SelectableTile(
        bookId = book.id,
        selection = selection,
        label = null,
        href = Routes.Book.detail(book.id).toUrl(),
        onOpen = { selection.press(book.id) { onOpenBook(book.id) } },
        classes = listOf("disc-card"),
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

/**
 * "Recently added", Discover's last section: the newest books on the server, each saying when it was
 * added, and "Newest in Library →", which opens the Library sorted newest first.
 */
@Composable
internal fun RecentlyAddedSection(
    state: RecentlyAddedUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenLibraryNewest: () -> Unit,
    selection: BookSelection?,
    onRetry: () -> Unit,
) {
    Section(
        DiscoverWords.RECENTLY_ADDED,
        styleClass = "disc-a-recent",
        actions = {
            AppLink(
                Routes.libraryNewestFirst.toUrl(),
                onOpen = onOpenLibraryNewest,
                attrs = { classes("disc-newest") },
            ) {
                Text(DiscoverWords.NEWEST_IN_LIBRARY)
            }
        },
    ) {
        when (state) {
            is RecentlyAddedUiState.Loading -> {
                CardSkeletons("disc-recent", captionLines = 3)
            }

            is RecentlyAddedUiState.Error -> {
                SectionError(DiscoverWords.ERROR_RECENT, onRetry = onRetry)
            }

            is RecentlyAddedUiState.Ready -> {
                if (state.isEmpty) {
                    QuietLine(DiscoverWords.NO_RECENTLY_ADDED)
                } else {
                    Div(attrs = { classes("disc-recent") }) {
                        state.books.forEach { book ->
                            key(book.id) {
                                RecentCard(
                                    book,
                                    DiscoverWords.added(relativeLastActiveInSentence(book.createdAt, nowMs)),
                                    onOpenBook,
                                    selection,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A recently added book: one link to it, its cover, title, author and when it was added. */
@Composable
private fun RecentCard(
    book: RecentlyAddedUiBook,
    addedText: String,
    onOpenBook: (String) -> Unit,
    selection: BookSelection?,
) {
    SelectableTile(
        bookId = book.id,
        selection = selection,
        label = null,
        href = Routes.Book.detail(book.id).toUrl(),
        onOpen = { selection.press(book.id) { onOpenBook(book.id) } },
        classes = listOf("disc-card"),
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
        Span(attrs = { classes("disc-pick-meta") }) { Text(addedText) }
    }
}

/**
 * "Shelves by others": other people's public shelves, grouped under the person who made them. Each
 * owner is one link to them (face, name, how many shelves); each shelf a link with its cover mosaic.
 *
 * Only public shelves reach here — the server filters, so this renders whatever it is given without
 * a privacy branch of its own. A client-side filter would be a second opinion about a question the
 * server has already answered, and the wrong place to be wrong.
 */
@Composable
internal fun ShelvesByOthersSection(
    state: DiscoverShelvesUiState,
    onOpenShelf: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Section(DiscoverWords.SHELVES_BY_OTHERS, styleClass = "disc-a-shelves") {
        when (state) {
            is DiscoverShelvesUiState.Loading -> {
                ShelvesSkeleton()
            }

            is DiscoverShelvesUiState.Error -> {
                SectionError(
                    if (state.needsConnection) DiscoverWords.SHELVES_NEED_CONNECTION else DiscoverWords.ERROR_SHELVES,
                    onRetry = onRetry,
                )
            }

            is DiscoverShelvesUiState.Ready -> {
                if (state.isEmpty) {
                    QuietLine(DiscoverWords.SHELVES_ONLY_YOU)
                } else {
                    state.users.forEach { owner ->
                        key(owner.user.id) {
                            Div(attrs = { classes("disc-owner-block") }) {
                                AppLink(
                                    Routes.profile(owner.user.id).toUrl(),
                                    onOpen = { onOpenProfile(owner.user.id) },
                                    attrs = { classes("disc-owner") },
                                ) {
                                    UserAvatar(
                                        userId = owner.user.id,
                                        name = owner.user.displayName,
                                        size = OWNER_AVATAR,
                                    )
                                    Span(attrs = { classes("disc-person-text") }) {
                                        Span(attrs = { classes("disc-person-name") }) { Text(owner.user.displayName) }
                                        Span(
                                            attrs = { classes("disc-when") },
                                        ) { Text(DiscoverWords.shelfCount(owner.shelves.size)) }
                                    }
                                }
                                Div(attrs = { classes("disc-shelves") }) {
                                    owner.shelves.forEach { shelf ->
                                        key(shelf.id) {
                                            AppLink(
                                                Routes.shelf(shelf.id).toUrl(),
                                                onOpen = { onOpenShelf(shelf.id) },
                                                attrs = { classes("disc-shelf") },
                                            ) {
                                                ShelfMosaic(shelf.covers)
                                                Span(attrs = { classes("disc-shelf-text") }) {
                                                    Span(attrs = { classes("disc-shelf-t") }) { Text(shelf.name) }
                                                    Span(
                                                        attrs = { classes("disc-shelf-sub") },
                                                    ) { Text(bookCountLabel(shelf.bookCount)) }
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

/** A shelf owner's avatar, in px. */
private const val OWNER_AVATAR = 32
