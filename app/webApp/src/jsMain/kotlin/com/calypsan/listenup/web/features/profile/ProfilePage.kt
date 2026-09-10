package com.calypsan.listenup.web.features.profile

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.model.ProfileShelfSummary
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.UserAvatar
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

/**
 * A listener: who they are, what they have been listening to, and what they have put on a shelf.
 *
 * Pure in [state]; the store wiring lives one level up.
 *
 * The same page serves your own profile and someone else's — `isOwnProfile` changes the heading and
 * nothing else, because the shared ViewModel already decides what a viewer is allowed to see
 * (own shelves come from the local mirror, another person's from the caller-accessible RPC). A web
 * page that re-derived that rule would be a second opinion about privacy, which is the last thing
 * that should have two.
 *
 * The pencil shows only on your own profile, and only ever opens your own form — both native
 * clients reach Edit Profile from here, and neither offers it on someone else's page.
 */
@Composable
fun ProfilePage(
    state: UserProfileUiState,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onRetry: () -> Unit,
    onEditProfile: () -> Unit,
) {
    Div(attrs = { classes("prof") }) {
        when (state) {
            is UserProfileUiState.Ready -> {
                ReadyContent(state, onOpenBook, onOpenShelf, onEditProfile)
            }

            is UserProfileUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("This profile can't be shown") }
                    P { Text(state.message) }
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", VALUE_BUTTON)
                        onClick { onRetry() }
                    }) { Text("Try again") }
                }
            }

            UserProfileUiState.Loading, UserProfileUiState.Idle -> {
                Div(attrs = { classes("skel", "prof-skel") })
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: UserProfileUiState.Ready,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onEditProfile: () -> Unit,
) {
    Hero(state, onEditProfile)

    if (state.recentBooks.isNotEmpty()) {
        Panel(title = if (state.isOwnProfile) "What you've been listening to" else "Recently listened") {
            Div(attrs = { classes("prof-books") }) {
                state.recentBooks.forEach { book -> RecentBook(book, onOpenBook) }
            }
        }
    }

    if (state.publicShelves.isNotEmpty()) {
        Panel(title = if (state.isOwnProfile) "Your shelves" else "Shelves") {
            Div(attrs = { classes("prof-shelves") }) {
                state.publicShelves.forEach { shelf -> ShelfRow(shelf, onOpenShelf) }
            }
        }
    }

    // Both panels absent is a real, common state — a new account. Saying so beats a page that
    // stops after the header and looks like it failed to finish loading.
    if (state.recentBooks.isEmpty() && state.publicShelves.isEmpty()) {
        NothingYet(state.isOwnProfile)
    }
}

/** Who this is, the four numbers that describe how they listen, and — if it is you — the pencil. */
@Composable
private fun Hero(
    state: UserProfileUiState.Ready,
    onEditProfile: () -> Unit,
) {
    Div(attrs = { classes("prof-hero") }) {
        UserAvatar(
            userId = state.userId,
            name = state.displayName,
            size = AVATAR_SIZE,
            avatarColor = state.avatarColor.takeIf { it.isNotBlank() },
        )
        Div(attrs = { classes("prof-idblock") }) {
            H1(attrs = { classes("prof-name") }) { Text(state.displayName) }
            // Absent, not an empty line: a tagline nobody has written is not a blank one.
            state.tagline?.takeIf { it.isNotBlank() }?.let { line ->
                P(attrs = { classes("prof-tagline") }) { Text(line) }
            }
            Div(attrs = { classes("prof-stats") }) {
                Stat(formatListenTime(state.totalListenTimeMs), "listened")
                Stat(
                    state.booksFinished.toString(),
                    if (state.booksFinished ==
                        1
                    ) {
                        "book finished"
                    } else {
                        "books finished"
                    },
                )
                // A streak of zero is not a streak. Printing "0 day streak" invents a fact about
                // someone who simply has not listened lately.
                // "1 day streak" and "9 day streak" both read correctly — the noun is attributive
                // here, so unlike "books finished" there is no plural to agree with.
                if (state.currentStreak > 0) Stat(state.currentStreak.toString(), "day streak")
                if (state.longestStreak > 0) Stat(state.longestStreak.toString(), "day record")
            }
        }
        if (state.isOwnProfile) {
            Button(attrs = {
                classes("iconbtn", "prof-edit")
                attr("type", VALUE_BUTTON)
                attr("aria-label", "Edit profile")
                attr("title", "Edit profile")
                onClick { onEditProfile() }
            }) { Icon(WebIcon.Pencil, size = EDIT_ICON_SIZE) }
        }
    }
}

/** A profile with nothing on it — a new account, or someone who shares nothing. */
@Composable
private fun NothingYet(isOwnProfile: Boolean) {
    Div(attrs = { classes("empty") }) {
        H3 { Text(if (isOwnProfile) "Nothing here yet" else "Nothing shared yet") }
        P {
            Text(
                if (isOwnProfile) {
                    "Books you listen to and shelves you make public will show up here."
                } else {
                    "This listener hasn't shared any books or shelves."
                },
            )
        }
    }
}

@Composable
private fun Stat(
    value: String,
    label: String,
) {
    Div(attrs = { classes("prof-stat") }) {
        Span(attrs = { classes("prof-stat-v") }) { Text(value) }
        Span(attrs = { classes("prof-stat-l") }) { Text(label) }
    }
}

@Composable
private fun RecentBook(
    book: ProfileRecentBook,
    onOpenBook: (String) -> Unit,
) {
    Button(attrs = {
        classes("prof-book")
        attr("type", VALUE_BUTTON)
        attr("aria-label", book.title)
        onClick { onOpenBook(book.bookId) }
    }) {
        // No cover hash on `ProfileRecentBook`, so this URL cannot be cache-busted. Acceptable
        // here and nowhere that matters more: a re-covered book shows its old art on someone's
        // profile until the cache expires, which is a cosmetic staleness on a secondary surface.
        Cover(
            title = book.title,
            imageUrl = coverUrl(book.bookId, null, BOOK_RUNG),
            size = BOOK_SIZE,
            radius = BOOK_RADIUS,
        )
        Span(attrs = { classes("prof-book-t") }) { Text(book.title) }
    }
}

@Composable
private fun ShelfRow(
    shelf: ProfileShelfSummary,
    onOpenShelf: (String) -> Unit,
) {
    Button(attrs = {
        classes("prof-shelf")
        attr("type", VALUE_BUTTON)
        onClick { onOpenShelf(shelf.id) }
    }) {
        Span(attrs = { classes("prof-shelf-n") }) { Text(shelf.name) }
        Span(attrs = { classes("prof-shelf-c") }) {
            Text(if (shelf.bookCount == 1) "1 book" else "${shelf.bookCount} books")
        }
    }
}

/** "92h 14m", or "None yet" for a listener who has not started — never a bare "0m". */
internal fun formatListenTime(totalMs: Long): String =
    if (totalMs <= 0) "None yet" else DurationFormatter.hoursMinutes(totalMs.milliseconds)

private const val AVATAR_SIZE = 96

private const val BOOK_SIZE = 104

private const val BOOK_RADIUS = 12

private const val BOOK_RUNG = 300

/** The pencil reads as an action, not a heading, so it sits below the name's weight. */
private const val EDIT_ICON_SIZE = 18

/** Every button here is an action, never a form submit. Named for the reason `TransportBar`
 *  names its own: four identical literals in one file is what the duplication rule is about. */
private const val VALUE_BUTTON = "button"
