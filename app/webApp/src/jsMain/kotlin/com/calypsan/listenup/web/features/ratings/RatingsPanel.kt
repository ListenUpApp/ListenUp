package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.ScoreRow
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.domain.averageLabel
import com.calypsan.listenup.domain.compactCount
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * The rating panel on Book Detail, yours first: your stars as the control itself — a click, a drag or
 * the keyboard, saved as it settles — then your note with "Add a note" / "Edit note" and "Remove"; then,
 * under a hairline, everyone else in labelled rows ([BookRatingsUiState.Ready.scoreRow]): the ListenUp
 * score (a button opening [RatingSourcesDialog]) and your listeners, in one notation. While Book Detail's
 * Hardcover check runs and no score shows yet, "Checking Hardcover…" holds the score's row.
 *
 * Loading draws **nothing**, like the Readers panel beside it — a panel that flashed "Not rated" and then
 * swapped in the rating you already left would be inviting you to do something done.
 *
 * Focus is never dropped on the floor. Remove hands it to the stars before its own button goes, and each
 * change of [starsFocusRequest] (the route bumps it when the toast's Undo puts a rating back) lands it
 * there too. One live region in the everyone half stays mounted, so the end of a Hardcover check is
 * heard: a status inserted together with its words is announced unreliably.
 *
 * [onSetStars], [onRate], [onClear] and [onRefreshExternal] are the ViewModel's own actions, passed
 * straight through; "Rating removed" with Undo is the route's toast. The two dialogs' open flags and the
 * drag's preview are the only state this layer owns. [nowMs] is what "Updated 3 days ago" counts from.
 */
@Composable
fun RatingsPanel(
    state: BookRatingsUiState,
    onSetStars: (halfStars: Int) -> Unit,
    onRate: (halfStars: Int, note: String?) -> Unit,
    onClear: () -> Unit,
    onRefreshExternal: () -> Unit = {},
    nowMs: Long = 0L,
    starsFocusRequest: Int = 0,
) {
    val ready = state as? BookRatingsUiState.Ready ?: return
    var isNoteDialogOpen by remember { mutableStateOf(false) }
    var isSourcesOpen by remember { mutableStateOf(false) }
    val stars = remember { StarsFocus() }

    LaunchedEffect(starsFocusRequest) {
        if (starsFocusRequest > 0) stars.focus()
    }

    Panel(title = "Ratings") {
        Div(attrs = { classes("rt") }) {
            Div(attrs = { classes("rt-body") }) {
                YourRating(
                    mine = ready.mine,
                    stars = stars,
                    onSetStars = onSetStars,
                    onEditNote = { isNoteDialogOpen = true },
                    onRemove = onClear,
                )
                Everyone(ready = ready, onOpenSources = { isSourcesOpen = true }, onRefreshExternal = onRefreshExternal)
            }
        }
    }

    RateBookDialog(
        open = isNoteDialogOpen,
        current = ready.mine,
        onSave = onRate,
        onClear = onClear,
        onDismiss = { isNoteDialogOpen = false },
    )

    RatingSourcesDialog(
        open = isSourcesOpen,
        ready = ready,
        nowMs = nowMs,
        onRefresh = onRefreshExternal,
        onDismiss = { isSourcesOpen = false },
    )
}

/** Where the "you" half's stars are, so focus can be handed to them. */
private class StarsFocus {
    var root: HTMLElement? = null

    fun focus() {
        (root?.querySelector("[role=slider]") as? HTMLElement)?.focus()
    }
}

@Composable
private fun YourRating(
    mine: ListenerRating?,
    stars: StarsFocus,
    onSetStars: (Int) -> Unit,
    onEditNote: () -> Unit,
    onRemove: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: mine?.halfStars ?: 0
    val isRated = shown >= ListenerRatingLimits.MIN_HALF_STARS

    Div(attrs = {
        classes("rt-you")
        ref { element ->
            stars.root = element
            onDispose { stars.root = null }
        }
    }) {
        Div(attrs = { classes("rt-you-top") }) {
            Span(attrs = { classes("rt-you-l") }) { Text("Your rating") }
            // The slider says its own value; this is for the eye.
            Span(attrs = {
                classes("rt-you-v")
                if (!isRated) classes("is-unrated")
                attr(ARIA_HIDDEN, "true")
            }) { Text(if (isRated) ListenerRatingLimits.starsLabel(shown.toDouble()) else "Not rated") }
        }
        RatingStars(
            halfStars = shown,
            onHalfStarsChange = { dragging = it },
            onHalfStarsCommit = { picked ->
                dragging = null
                onSetStars(picked)
            },
            label = "Your rating",
        )
        Div(attrs = {
            classes("rt-keys")
            attr(ARIA_HIDDEN, "true")
        }) {
            KeyCap("←")
            KeyCap("→")
            Text("half a star ·")
            KeyCap("Home")
            KeyCap("End")
        }
        if (mine != null) {
            NoteAndActions(
                mine = mine,
                onEditNote = onEditNote,
                onRemove = {
                    // The button is about to go: hand focus to the stars first, or it falls to the page.
                    stars.focus()
                    onRemove()
                },
            )
        } else {
            Div(attrs = { classes("rt-hint") }) { Text("Tap a star. Drag for half stars.") }
        }
    }
}

@Composable
private fun KeyCap(label: String) {
    Span(attrs = { classes("rt-kbd") }) { Text(label) }
}

@Composable
private fun NoteAndActions(
    mine: ListenerRating,
    onEditNote: () -> Unit,
    onRemove: () -> Unit,
) {
    mine.note?.let { note -> P(attrs = { classes("rt-note") }) { Text("“$note”") } }
    Div(attrs = { classes("rt-you-acts") }) {
        Button(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = onEditNote) {
            Text(if (mine.note == null) "Add a note" else "Edit note")
        }
        Button(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = onRemove, attrs = { classes("rt-quiet") }) {
            Text("Remove")
        }
    }
}

@Composable
private fun Everyone(
    ready: BookRatingsUiState.Ready,
    onOpenSources: () -> Unit,
    onRefreshExternal: () -> Unit,
) {
    val scoreRow = ready.scoreRow
    val check = remember { CheckMemory() }
    Div(attrs = { classes("rt-ev") }) {
        Div(attrs = {
            classes("rt-sr")
            attr("role", "status")
        }) { Text(check.announcement(scoreRow)) }
        when (scoreRow) {
            is ScoreRow.Shown -> {
                ScoreButton(scoreRow.score, ready, onOpenSources)
            }

            ScoreRow.Checking -> {
                CheckingRow()
            }

            ScoreRow.NoRatings -> {
                Div(attrs = { classes("rt-nobody") }) {
                    Muted(NO_RATINGS)
                    if (ready.showsInlineRefresh) RefreshAction(ready.isRefreshingExternal, onRefreshExternal)
                }
            }

            ScoreRow.Absent -> {
                Unit
            }
        }
        ready.listeners?.let { ListenersRow(it) }
        if (scoreRow == ScoreRow.Absent && ready.showsInlineRefresh) {
            RefreshAction(ready.isRefreshingExternal, onRefreshExternal)
        }
    }
}

/**
 * What the everyone half's live region says: "Checking Hardcover…" while the check runs, then what it
 * found once it ends. Silent for a score that was already showing — nothing new to hear.
 */
private class CheckMemory {
    private var sawCheck = false

    fun announcement(row: ScoreRow): String {
        if (row == ScoreRow.Checking) sawCheck = true
        return when {
            row == ScoreRow.Checking -> CHECKING
            !sawCheck -> ""
            row is ScoreRow.Shown -> "ListenUp score: ${averageLabel(row.score.average)} out of 5 stars."
            row == ScoreRow.NoRatings -> "$NO_RATINGS."
            else -> ""
        }
    }
}

@Composable
private fun ScoreButton(
    score: CombinedScore,
    ready: BookRatingsUiState.Ready,
    onOpen: () -> Unit,
) {
    val average = averageLabel(score.average)
    val sources = scoreSources(ready)
    val count = ratingsCount(score.count)
    val spoken =
        "ListenUp score: Rated $average out of 5 stars from $count." + if (sources.isEmpty()) "" else " $sources."
    DomButton(attrs = {
        classes("rt-er", "rt-score")
        attr("type", "button")
        attr("aria-label", spoken)
        onClick { onOpen() }
    }) {
        Figure(average)
        Span(attrs = { classes("rt-et") }) {
            Span(attrs = { classes("rt-eh") }) { Text("ListenUp score") }
            Muted(if (sources.isEmpty()) count else "$count · $sources")
        }
        Icon(WebIcon.ChevronRight, size = CHEVRON_SIZE, attrs = { classes("rt-chev") })
    }
}

/** The score's row while Hardcover is checked. The live region beside it speaks; this is for the eye. */
@Composable
private fun CheckingRow() {
    Div(attrs = {
        classes("rt-er")
        attr(ARIA_HIDDEN, "true")
    }) {
        Span(attrs = { classes("rt-en") }) {
            Span(attrs = { classes("rt-skel") })
        }
        Span(attrs = { classes("rt-et") }) {
            Span(attrs = { classes("rt-eh") }) { Text("ListenUp score") }
            Muted(CHECKING)
        }
    }
}

/**
 * "★ 4.0 · Your listeners · 3 ratings"; a screen reader hears "Your listeners: 4.0 out of 5 stars, from 3
 * ratings" instead of the star and the dots.
 */
@Composable
private fun ListenersRow(listeners: ListenerAverage) {
    val label = RatingLabels.listenerAverageLabel(listeners)
    val count = ratingsCount(listeners.count)
    Div(attrs = { classes("rt-er") }) {
        Span(attrs = { classes("rt-sr") }) { Text("Your listeners: $label out of 5 stars, from $count") }
        Span(attrs = {
            classes("rt-er-vis")
            attr(ARIA_HIDDEN, "true")
        }) {
            Figure(label)
            Span(attrs = { classes("rt-et") }) {
                Span(attrs = { classes("rt-eh") }) { Text("Your listeners") }
                Muted(count)
            }
        }
    }
}

/** A quiet secondary line: a row's count and sources, or "No ratings yet". */
@Composable
private fun Muted(text: String) {
    Span(attrs = { classes("rt-mut") }) { Text(text) }
}

@Composable
private fun Figure(average: String) {
    Span(attrs = { classes("rt-en") }) {
        Span(attrs = {
            classes("rt-star")
            attr(ARIA_HIDDEN, "true")
        }) { Text("★") }
        Text(average)
    }
}

/**
 * The quiet "Refresh ratings", or "Refreshing…" while one is in flight. Busy is `aria-disabled` and an
 * ignored press, never `disabled`: a disabled button drops the focus that was on it, so a keyboard user
 * who pressed it would be thrown back to the top of the page.
 */
@Composable
internal fun RefreshAction(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Button(
        kind = ButtonKind.Ghost,
        size = ButtonSize.Sm,
        onClick = { if (!isRefreshing) onRefresh() },
        attrs = { if (isRefreshing) attr("aria-disabled", "true") },
    ) {
        Text(if (isRefreshing) "Refreshing…" else "Refresh ratings")
    }
}

/** "12k ratings", or "1 rating". */
internal fun ratingsCount(count: Int): String = if (count == 1) "1 rating" else "${compactCount(count)} ratings"

/** "Audible, Hardcover, your listeners": the sources the score draws on, in the breakdown's order. */
private fun scoreSources(ready: BookRatingsUiState.Ready): String =
    (
        ready.outsideRatingsInScore.map { sourceDisplayName(it.source) } +
            listOfNotNull("your listeners".takeIf { ready.listenersInScore })
    ).joinToString(", ")

private const val NO_RATINGS = "No ratings yet"

private const val CHECKING = "Checking Hardcover…"

private const val CHEVRON_SIZE = 16

private const val ARIA_HIDDEN = "aria-hidden"
