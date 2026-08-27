package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.client.presentation.discover.activityParts
import com.calypsan.listenup.client.presentation.discover.leaderboardEntries
import com.calypsan.listenup.client.presentation.discover.leaderboardLabel
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.shelf.bookCountLabel
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The shared empty-state block, borrowed rather than restyled per section. */
private const val EMPTY_CLASS = "empty"

/** Cover width for a discovery card, in px. Portrait 2:3, so the frame is 1.5x as tall. */
private const val CARD_COVER_WIDTH = 140

private const val CARD_COVER_HEIGHT = 210

/** Cover width for a listener row's book, in px. */
private const val LISTENER_COVER_WIDTH = 56

private const val LISTENER_COVER_HEIGHT = 84

/**
 * Discover — the social half of the app, and the only screen that shows the reader other people.
 *
 * Five sections, each rendering its own sealed state. That independence is the point: the
 * leaderboard failing must not blank the listeners row, and an activity feed still loading must not
 * hold back the books. One section's bad day costs exactly that section.
 *
 * Every sentence, stat and ranking on this page comes from a shared projection
 * (`activityParts`, `leaderboardLabel`, `leaderboardEntries`, `relativeLastActive`) rather than
 * being restated here. This screen is almost entirely copy, and copy that exists twice is copy that
 * disagrees with itself the first time either side is reworded.
 *
 * The shelves section groups by owner rather than listing shelves flat: on a shared server the
 * interesting unit is a person's taste, and a flat list of forty shelves says nothing about whose
 * they are.
 *
 * [nowMs] is passed in rather than read here so a row cannot flicker "3 days ago" to "4 days ago"
 * mid-recomposition, and so a spec can pin the clock. Same reasoning the Compose screen gives.
 */
@Composable
fun DiscoverPage(
    books: DiscoverBooksUiState,
    recentlyAdded: RecentlyAddedUiState,
    currentlyListening: CurrentlyListeningUiState,
    leaderboard: LeaderboardUiState,
    activity: ActivityFeedUiState,
    shelves: DiscoverShelvesUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    onSelectCategory: (LeaderboardCategory) -> Unit,
) {
    Div(attrs = { classes("disc") }) {
        H1(attrs = { classes("disc-title") }) { Text("Discover") }

        CurrentlyListeningSection(currentlyListening, nowMs, onOpenBook)
        DiscoverBooksSection(books, onOpenBook)
        RecentlyAddedSection(recentlyAdded, onOpenBook)
        SharedShelvesSection(shelves, onOpenShelf)
        LeaderboardSection(leaderboard, onSelectPeriod, onSelectCategory)
        ActivityFeedSection(activity, nowMs, onOpenBook)
    }
}

/**
 * "What others are listening to" — the section that makes this a shared library rather than a
 * private one, which is why it leads.
 *
 * Two kinds of row in one list: anyone with a live session, marked "Listening now", then everyone
 * else on the book they last played. [CurrentlyListeningUiSession.isLive] says which.
 */
@Composable
private fun CurrentlyListeningSection(
    state: CurrentlyListeningUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
) {
    Section("What others are listening to") {
        when (state) {
            is CurrentlyListeningUiState.Loading -> {
                SectionSkeleton()
            }

            is CurrentlyListeningUiState.Error -> {
                SectionError(state.message)
            }

            is CurrentlyListeningUiState.Ready -> {
                if (state.isEmpty) {
                    Empty("Nobody else is listening yet", "When they do, you will see them here.")
                } else {
                    Div(attrs = { classes("disc-listeners") }) {
                        state.sessions.forEach { session -> ListenerCard(session, nowMs, onOpenBook) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListenerCard(
    session: CurrentlyListeningUiSession,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
) {
    Button(attrs = {
        classes("disc-listener")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-label", "${session.displayName} — ${session.bookTitle}")
        onClick { onOpenBook(session.bookId) }
    }) {
        Cover(
            title = session.bookTitle,
            imageUrl = coverUrl(session.bookId, session.coverHash, width = LISTENER_COVER_WIDTH),
            size = LISTENER_COVER_WIDTH,
            height = LISTENER_COVER_HEIGHT,
        )
        Div(attrs = { classes("disc-listener-text") }) {
            Span(attrs = { classes("disc-listener-who") }) { Text(session.displayName) }
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
private fun DiscoverBooksSection(
    state: DiscoverBooksUiState,
    onOpenBook: (String) -> Unit,
) {
    Section("Something new") {
        when (state) {
            is DiscoverBooksUiState.Loading -> {
                SectionSkeleton()
            }

            is DiscoverBooksUiState.Error -> {
                SectionError(state.message)
            }

            is DiscoverBooksUiState.Ready -> {
                if (state.isEmpty) {
                    Empty("Nothing left to discover", "You have started everything in the library.")
                } else {
                    Div(attrs = { classes("disc-grid") }) {
                        state.books.forEach { book ->
                            BookCard(book.id, book.title, book.authorName, book.coverHash, onOpenBook)
                        }
                    }
                }
            }
        }
    }
}

/** "Recently added" — what the library gained while the reader was away. */
@Composable
private fun RecentlyAddedSection(
    state: RecentlyAddedUiState,
    onOpenBook: (String) -> Unit,
) {
    Section("Recently added") {
        when (state) {
            is RecentlyAddedUiState.Loading -> {
                SectionSkeleton()
            }

            is RecentlyAddedUiState.Error -> {
                SectionError(state.message)
            }

            is RecentlyAddedUiState.Ready -> {
                if (state.isEmpty) {
                    Empty("Nothing new yet", "Books appear here as they are added to the library.")
                } else {
                    Div(attrs = { classes("disc-grid") }) {
                        state.books.forEach { book ->
                            BookCard(book.id, book.title, book.authorName, book.coverHash, onOpenBook)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    bookId: String,
    title: String,
    authorName: String?,
    coverHash: String?,
    onOpenBook: (String) -> Unit,
) {
    Button(attrs = {
        classes("disc-card")
        attr(ATTR_TYPE, VALUE_BUTTON)
        onClick { onOpenBook(bookId) }
    }) {
        Cover(
            title = title,
            imageUrl = coverUrl(bookId, coverHash, width = CARD_COVER_WIDTH),
            size = CARD_COVER_WIDTH,
            height = CARD_COVER_HEIGHT,
        )
        Span(attrs = { classes("disc-card-t") }) { Text(title) }
        authorName?.let { Span(attrs = { classes("disc-card-sub") }) { Text(it) } }
    }
}

/**
 * The leaderboard: a period, a category, and a ranking.
 *
 * Both controls are cheap — all three categories arrive in one snapshot, so switching a tab is a
 * list pick rather than a query. The period genuinely re-queries, which is why it reads as the
 * outer control.
 */
@Composable
private fun LeaderboardSection(
    state: LeaderboardUiState,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    onSelectCategory: (LeaderboardCategory) -> Unit,
) {
    Section("Leaderboard") {
        when (state) {
            is LeaderboardUiState.Loading -> {
                SectionSkeleton()
            }

            is LeaderboardUiState.Empty -> {
                Empty("No listening recorded yet", "The board fills in as people listen.")
            }

            is LeaderboardUiState.Error -> {
                SectionError(
                    if (state.isRetryable) {
                        "The leaderboard could not be loaded. It will try again shortly."
                    } else {
                        "The leaderboard is unavailable."
                    },
                )
            }

            is LeaderboardUiState.Data -> {
                Div(attrs = { classes("disc-lb-controls") }) {
                    Div(attrs = { classes("disc-chips") }) {
                        PERIODS.forEach { (period, label) ->
                            Chip(label, selected = period == state.period) { onSelectPeriod(period) }
                        }
                    }
                    Div(attrs = { classes("disc-chips") }) {
                        LeaderboardCategory.entries.forEach { category ->
                            Chip(categoryLabel(category), selected = category == state.category) {
                                onSelectCategory(category)
                            }
                        }
                    }
                }

                val entries = leaderboardEntries(state.snapshot, state.category)
                if (entries.isEmpty()) {
                    Empty("Nothing in this category yet", "Try another period.")
                } else {
                    Div(attrs = { classes("disc-lb") }) {
                        entries.forEach { entry ->
                            Div(attrs = { classes("disc-lb-row") }) {
                                Span(attrs = { classes("disc-lb-rank") }) { Text("${entry.rank}") }
                                Span(attrs = { classes("disc-lb-name") }) { Text(entry.displayName) }
                                Span(attrs = { classes("disc-lb-stat", "mono") }) {
                                    Text(leaderboardLabel(entry, state.category))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The feed: who did what, most recent first. */
@Composable
private fun ActivityFeedSection(
    state: ActivityFeedUiState,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
) {
    Section("Recent activity") {
        when (state) {
            is ActivityFeedUiState.Loading -> {
                SectionSkeleton()
            }

            is ActivityFeedUiState.Error -> {
                SectionError(state.message)
            }

            is ActivityFeedUiState.Ready -> {
                if (state.isEmpty) {
                    Empty("Nothing has happened yet", "Activity from everyone on this server shows up here.")
                } else {
                    Div(attrs = { classes("disc-feed") }) {
                        state.activities.forEach { item -> ActivityRow(item, nowMs, onOpenBook) }
                    }
                }
            }
        }
    }
}

/**
 * One activity, as a sentence.
 *
 * A row with a book is clickable; one without (a streak, a join) is a plain div, because a control
 * that looks pressable and does nothing is worse than plain text.
 */
@Composable
private fun ActivityRow(
    item: ActivityUiModel,
    nowMs: Long,
    onOpenBook: (String) -> Unit,
) {
    val parts = activityParts(item)
    val bookId = item.bookId

    val body: @Composable () -> Unit = {
        Span(attrs = { classes("disc-feed-line") }) {
            Span(attrs = { classes("disc-feed-who") }) { Text(item.userDisplayName) }
            Text(" ${parts.predicate}")
            parts.highlight?.let { highlight ->
                Text(" ")
                Span(attrs = { classes("disc-feed-hi") }) { Text(highlight) }
            }
            if (parts.suffix.isNotEmpty()) {
                Text(parts.suffix)
            }
        }
        Span(attrs = { classes("disc-when") }) { Text(relativeLastActive(item.occurredAt, nowMs)) }
    }

    if (bookId == null) {
        Div(attrs = { classes("disc-feed-row") }) { body() }
    } else {
        Button(attrs = {
            classes("disc-feed-row", "is-open")
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { onOpenBook(bookId) }
        }) {
            body()
        }
    }
}

// ── Shared section furniture ────────────────────────────────────────────────

@Composable
private fun Section(
    heading: String,
    content: @Composable () -> Unit,
) {
    Div(attrs = { classes("disc-section") }) {
        H3(attrs = { classes("disc-section-h") }) { Text(heading) }
        content()
    }
}

/**
 * The pre-first-emission state, said with a shape rather than a word.
 *
 * Deliberately silent about why: at this point the section does not know whether it is empty,
 * loading slowly or broken, and guessing out loud is how a page ends up lying.
 */
@Composable
private fun SectionSkeleton() {
    Div(attrs = { classes("skel", "disc-skel") })
}

@Composable
private fun SectionError(message: String) {
    P(attrs = { classes("disc-error") }) { Text(message) }
}

@Composable
private fun Empty(
    heading: String,
    detail: String,
) {
    Div(attrs = { classes(EMPTY_CLASS) }) {
        H3 { Text(heading) }
        P { Text(detail) }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Button(attrs = {
        classes("disc-chip")
        if (selected) classes("is-on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        // Pressed, not selected: these are buttons in a group, not a listbox, and `aria-pressed`
        // is what a screen reader reads as "on" for a toggle-shaped control.
        attr("aria-pressed", selected.toString())
        onClick { onSelect() }
    }) {
        Text(label)
    }
}

/** The periods the board offers, with the words the tabs use for them. */
private val PERIODS: List<Pair<LeaderboardPeriod, String>> =
    listOf(
        LeaderboardPeriod.Week to "Week",
        LeaderboardPeriod.Month to "Month",
        LeaderboardPeriod.Year to "Year",
        LeaderboardPeriod.AllTime to "All time",
    )

private fun categoryLabel(category: LeaderboardCategory): String =
    when (category) {
        LeaderboardCategory.Time -> "Time"
        LeaderboardCategory.Books -> "Books"
        LeaderboardCategory.Streak -> "Streak"
    }

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

/**
 * Other people's shelves, grouped under the person who made them.
 *
 * Only public shelves reach here — the server filters, so this renders whatever it is given without
 * a privacy branch of its own. A client-side filter would be a second opinion about a question the
 * server has already answered, and the wrong place to be wrong.
 */
@Composable
private fun SharedShelvesSection(
    state: DiscoverShelvesUiState,
    onOpenShelf: (String) -> Unit,
) {
    Section("Shelves from others") {
        when (state) {
            is DiscoverShelvesUiState.Loading -> {
                SectionSkeleton()
            }

            is DiscoverShelvesUiState.Error -> {
                SectionError(state.message)
            }

            is DiscoverShelvesUiState.Ready -> {
                if (state.isEmpty) {
                    Empty("No shared shelves yet", "Shelves other people make public show up here.")
                } else {
                    state.users.forEach { owner ->
                        Div(attrs = { classes("disc-shelf-owner") }) {
                            Span(attrs = { classes("disc-shelf-who") }) { Text(owner.user.displayName) }
                            Div(attrs = { classes("disc-shelves") }) {
                                owner.shelves.forEach { shelf ->
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
