package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.TextAreaField
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

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

/** Room for a couple of sentences — the note is capped at 280 characters. */
private const val NOTE_ROWS = 3

/** Every button here is an action, never a form submit. */
private const val TYPE_BUTTON = "button"
