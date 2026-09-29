package com.calypsan.listenup.web.features.ratings

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.ScoreSource
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.domain.averageLabel
import com.calypsan.listenup.domain.compactCount
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.TextAreaField
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

/**
 * The rating panel on Book Detail, above Readers: the ListenUp score's headline (when any enabled
 * outside source has rated the book), then your listeners' average, then either "Rate" or your
 * own stars with "Edit". [RateBookDialog] opens from the listener half; [BreakdownDialog] opens
 * from the headline. Before any score exists, an admin sees [RefreshRatingsAction] where the
 * headline would sit instead — there is no headline yet to open the breakdown from. A book only
 * your listeners have rated has no headline either: their own line already says what they think,
 * and a second number read off ListenUp's curve would look like a contradiction.
 *
 * Loading draws **nothing**, like the Readers panel beside it — a panel that flashed "Rate" and
 * then swapped it for the rating you already left would be inviting you to do something done.
 *
 * [onRate], [onClear] and [onRefreshExternal] are the ViewModel's own actions, passed straight
 * through; the two dialogs' open flags are the only state this layer owns.
 */
@Composable
fun RatingsPanel(
    state: BookRatingsUiState,
    onRate: (halfStars: Int, note: String?) -> Unit,
    onClear: () -> Unit,
    onRefreshExternal: () -> Unit = {},
) {
    val ready = state as? BookRatingsUiState.Ready ?: return
    var isDialogOpen by remember { mutableStateOf(false) }
    var isBreakdownOpen by remember { mutableStateOf(false) }

    Panel(title = "Ratings") {
        Div(attrs = { classes("rt") }) {
            // A listeners-only score would repeat the listeners' line below as a second,
            // recalibrated number; their line says it plainly instead.
            val external = ready.external?.takeUnless { it.isListenersOnly }
            if (external != null) {
                ExternalHeadline(external, onOpen = { isBreakdownOpen = true })
            } else if (ready.canRefresh) {
                RefreshRatingsAction(isRefreshing = ready.isRefreshingExternal, onRefresh = onRefreshExternal)
            }
            ready.listeners?.let { ListenersAverage(it) }
            val mine = ready.mine
            if (mine == null) {
                Button(
                    kind = ButtonKind.Secondary,
                    onClick = { isDialogOpen = true },
                    attrs = {
                        classes("rt-rate")
                    },
                ) { Text("Rate") }
            } else {
                Div(attrs = { classes("rt-mine") }) {
                    Span(attrs = { classes("rt-mine-l") }) { Text("Your rating") }
                    RatingStars(halfStars = mine.halfStars)
                    Button(attrs = {
                        classes("rdr-all", "rt-edit")
                        attr("type", TYPE_BUTTON)
                        onClick { isDialogOpen = true }
                    }) { Text("Edit") }
                }
            }
        }
    }

    RateBookDialog(
        open = isDialogOpen,
        current = ready.mine,
        onSave = onRate,
        onClear = onClear,
        onDismiss = { isDialogOpen = false },
    )

    BreakdownDialog(
        open = isBreakdownOpen,
        breakdown = ready.breakdown,
        score = ready.external,
        listeners = ready.listeners,
        canRefresh = ready.canRefresh,
        isRefreshing = ready.isRefreshingExternal,
        onRefresh = onRefreshExternal,
        onDismiss = { isBreakdownOpen = false },
    )
}

/**
 * The quiet action shown where [ExternalHeadline] would sit before any enabled source has rated
 * the book — admin only ([BookRatingsUiState.Ready.canRefresh]). Lets an admin fetch a first score
 * directly, rather than being stranded behind a headline that only exists once one arrives.
 *
 * [isRefreshing] is the ViewModel's own
 * [com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState.Ready.isRefreshingExternal] —
 * bound the same way [BreakdownDialog]'s own refresh button binds it.
 */
@Composable
private fun RefreshRatingsAction(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Button(
        kind = ButtonKind.Secondary,
        onClick = onRefresh,
        enabled = !isRefreshing,
        attrs = { classes("rt-refresh-first") },
    ) { Text(refreshLabel(isRefreshing)) }
}

/** "Refresh ratings", or "Refreshing…" while [isRefreshing] — the one refresh action label. */
private fun refreshLabel(isRefreshing: Boolean): String = if (isRefreshing) "Refreshing…" else "Refresh ratings"

/**
 * The ListenUp score's headline: "★ 4.4 · 12k ratings" on screen; "Rated 4.4 out of 5 stars from
 * 12k ratings" to a screen reader (en.json's `book.detail_rating_external_a11y`). A button, not a label — tapping it opens [BreakdownDialog].
 *
 * The average is [averageLabel], never [ListenerRatingLimits.starsLabel]: the outside score is
 * a continuous average, not a half-star pick, and rounding it to the nearest half would print
 * "4.5" under a headline the design calls "★ 4.4".
 */
@Composable
private fun ExternalHeadline(
    external: CombinedScore,
    onOpen: () -> Unit,
) {
    val average = averageLabel(external.average)
    val count = compactCount(external.count)
    val visible = if (external.count == 1) "$average · 1 rating" else "$average · $count ratings"
    val a11y =
        if (external.count == 1) {
            "Rated $average out of 5 stars from 1 rating"
        } else {
            "Rated $average out of 5 stars from $count ratings"
        }
    Button(
        kind = ButtonKind.Secondary,
        onClick = onOpen,
        label = a11y,
        attrs = { classes("rt-external") },
    ) {
        Span(attrs = { attr("aria-hidden", "true") }) {
            Span(attrs = { classes("rt-ext-star") }) { Text("★ ") }
            Text(visible)
        }
    }
}

/**
 * The ListenUp score's breakdown, opened from [ExternalHeadline]: "Combined from N sources" when
 * [score] has more than one, one row per outside source in [breakdown] with its share of the score
 * ("Audible · 4.7 · 1k · 38%"), a "Your listeners" row when they are part of it ("Your listeners ·
 * 4.0 · 3 · 20%"), and — admin only — a refresh. Each row's average is on the source's own curve,
 * not ListenUp's.
 *
 * [onRefresh] calls [com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel.refreshExternal];
 * [isRefreshing] is the ViewModel's own
 * [com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState.Ready.isRefreshingExternal] —
 * true while that RPC is in flight, whether or not any score ends up changing. A failure still
 * surfaces normally, through the app's error bus.
 */
@Composable
private fun BreakdownDialog(
    open: Boolean,
    breakdown: List<ExternalRating>,
    score: CombinedScore?,
    listeners: ListenerAverage?,
    canRefresh: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return

    ModalDialog(open = true, title = "Ratings", onDismiss = onDismiss) {
        if (score != null && score.sourceCount >= 2) {
            Div(attrs = { classes("rt-combined") }) { Text("Combined from ${score.sourceCount} sources") }
        }
        Div(attrs = { classes("rt-sources") }) {
            breakdown.forEach { rating ->
                key(rating.source) {
                    SourceRow(
                        label = sourceDisplayName(rating.source),
                        average = rating.average,
                        count = rating.count,
                        share = score?.shares?.get(ScoreSource.Outside(rating.source)),
                    )
                }
            }
            val listenersShare = score?.shares?.get(ScoreSource.Listeners)
            if (listeners != null && listenersShare != null) {
                SourceRow(
                    label = "Your listeners",
                    average = listeners.averageHalfStars / 2,
                    count = listeners.count,
                    share = listenersShare,
                )
            }
        }
        if (canRefresh) {
            Button(
                kind = ButtonKind.Secondary,
                onClick = onRefresh,
                enabled = !isRefreshing,
                attrs = { classes("rt-refresh") },
            ) { Text(refreshLabel(isRefreshing)) }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(kind = ButtonKind.Primary, onClick = onDismiss) { Text("Close") }
        }
    }
}

/**
 * One source's row: "Audible · 4.7 · 1k", and its share of the score as a whole percent when it
 * has one ("Audible · 4.7 · 1k · 38%").
 */
@Composable
private fun SourceRow(
    label: String,
    average: Double,
    count: Int,
    share: Double?,
) {
    val figures = "$label · ${averageLabel(average)} · ${compactCount(count)}"
    Div(attrs = { classes("rt-source-row") }) {
        Text(if (share == null) figures else "$figures · ${(share * PERCENT).roundToInt()}%")
    }
}

/** The source name every platform shows: "Audible", "Hardcover", "Goodreads". */
private fun sourceDisplayName(source: ExternalRatingSource): String =
    when (source) {
        ExternalRatingSource.AUDIBLE -> "Audible"
        ExternalRatingSource.HARDCOVER -> "Hardcover"
        ExternalRatingSource.GOODREADS -> "Goodreads"
        ExternalRatingSource.UNKNOWN -> "Unknown"
    }

/**
 * "Your listeners ★ 4 (3)" on screen; "Your listeners: 4 out of 5 stars, from 3 ratings" to a
 * screen reader, which would otherwise read the star and the brackets literally.
 */
@Composable
private fun ListenersAverage(listeners: ListenerAverage) {
    val stars = ListenerRatingLimits.starsLabel(listeners.averageHalfStars)
    val ratings = if (listeners.count == 1) "1 rating" else "${listeners.count} ratings"
    Div(attrs = { classes("rt-avg") }) {
        Span(attrs = { classes("rt-sr") }) { Text("Your listeners: $stars out of 5 stars, from $ratings") }
        Span(attrs = { attr("aria-hidden", "true") }) {
            Text("Your listeners ")
            Span(attrs = { classes("rt-avg-star") }) { Text("★") }
            Text(" $stars (${listeners.count})")
        }
    }
}

/**
 * Rate this book: input stars, an optional note with an "n/280" counter, Save, and — only when you
 * have already rated it — Remove rating. Opens on your [current] rating, or on no stars.
 *
 * Save stays genuinely disabled below one star rather than accepting the press and letting the
 * server refuse it. Save and Remove both close the dialog: the write is offline-first, so the
 * panel behind it already shows the result.
 *
 * The note's cap is the textarea's own `maxlength`, which refuses the *inserted* text at the limit
 * — typing mid-note never trims its ending. The counter counts UTF-16 units, as the server does.
 */
@Composable
fun RateBookDialog(
    open: Boolean,
    current: ListenerRating?,
    onSave: (halfStars: Int, note: String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return
    var halfStars by remember { mutableIntStateOf(current?.halfStars ?: 0) }
    var note by remember { mutableStateOf(current?.note.orEmpty()) }

    ModalDialog(open = true, title = "Rate this book", onDismiss = onDismiss) {
        Div(attrs = { classes("rt-input") }) {
            RatingStars(halfStars = halfStars, onHalfStarsChange = { halfStars = it })
        }
        TextAreaField(
            label = "Add a note (optional)",
            value = note,
            onInput = { note = it },
            rows = NOTE_ROWS,
            maxLength = ListenerRatingLimits.NOTE_MAX_CHARS,
        )
        Div(attrs = { classes("rt-count") }) {
            Text("${note.length}/${ListenerRatingLimits.NOTE_MAX_CHARS}")
        }
        if (current != null) {
            Button(attrs = {
                classes("rt-remove")
                attr("type", TYPE_BUTTON)
                onClick {
                    onClear()
                    onDismiss()
                }
            }) { Text("Remove rating") }
        }
        DialogActions(
            confirmLabel = "Save",
            confirmEnabled =
                halfStars >= ListenerRatingLimits.MIN_HALF_STARS &&
                    note.length <= ListenerRatingLimits.NOTE_MAX_CHARS,
            onConfirm = {
                onSave(halfStars, ListenerRatingLimits.normalizeNote(note))
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/** A share of the score, as a whole percent. */
private const val PERCENT = 100

/** Room for a couple of sentences — the note is capped at 280 characters. */
private const val NOTE_ROWS = 3

/** Every button here is an action, never a form submit. */
private const val TYPE_BUTTON = "button"
