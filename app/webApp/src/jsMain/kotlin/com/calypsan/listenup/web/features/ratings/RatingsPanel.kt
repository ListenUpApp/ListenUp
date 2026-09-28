package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.TextAreaField
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The rating panel on Book Detail, above Readers: your listeners' average when anyone has rated
 * the book, then either "Rate" or your own stars with "Edit". Both open [RateBookDialog].
 *
 * Loading draws **nothing**, like the Readers panel beside it — a panel that flashed "Rate" and
 * then swapped it for the rating you already left would be inviting you to do something done.
 *
 * [onRate] and [onClear] are the ViewModel's own actions, passed straight through; the dialog's
 * open flag is the only state this layer owns.
 */
@Composable
fun RatingsPanel(
    state: BookRatingsUiState,
    onRate: (halfStars: Int, note: String?) -> Unit,
    onClear: () -> Unit,
) {
    val ready = state as? BookRatingsUiState.Ready ?: return
    var isDialogOpen by remember { mutableStateOf(false) }

    Panel(title = "Ratings") {
        Div(attrs = { classes("rt") }) {
            ready.listeners?.let { ListenersAverage(it) }
            val mine = ready.mine
            if (mine == null) {
                Button(attrs = {
                    classes("btn-o", "rt-rate")
                    attr("type", "button")
                    onClick { isDialogOpen = true }
                }) { Text("Rate") }
            } else {
                Div(attrs = { classes("rt-mine") }) {
                    Span(attrs = { classes("rt-mine-l") }) { Text("Your rating") }
                    RatingStars(halfStars = mine.halfStars)
                    Button(attrs = {
                        classes("rdr-all", "rt-edit")
                        attr("type", "button")
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
                attr("type", "button")
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

/** Room for a couple of sentences — the note is capped at 280 characters. */
private const val NOTE_ROWS = 3
