package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop

/**
 * The text of every edit made to this state after the moment of collection, as plain strings.
 *
 * The state's current text is NOT emitted: a screen that seeds a field with its saved value has
 * not edited anything, so a handler with a side effect (dismiss the error banner, clear the token
 * error) must not fire at startup. Selection-only changes do not emit either — [snapshotFlow]
 * de-duplicates on the text, so moving the caret is silent.
 */
fun TextFieldState.edits(): Flow<String> = snapshotFlow { text.toString() }.drop(1)

/**
 * Forwards each edit of this state to [onEdited] for as long as the call site is composed.
 *
 * This is how a screen-owned field tells its ViewModel about typing: the field owns the text and
 * the caret, nothing echoes back, so a slow collector can never reset what the person is typing.
 * The latest [onEdited] is always the one called, so it may capture changing state freely.
 */
@Composable
fun TextFieldState.ForwardEdits(onEdited: (String) -> Unit) {
    val latest by rememberUpdatedState(onEdited)
    LaunchedEffect(this) { edits().collect { latest(it) } }
}

/**
 * A field state seeded with [initial] that tells [onEdited] about every later edit.
 *
 * For a field whose truth lives in a ViewModel: the field is seeded once from the ViewModel's value
 * and owns the text from there, so a slow ViewModel can never reset what the person is typing. A
 * new [key] starts a fresh state seeded with [initial] (a different record, or data that arrived
 * after the field first composed) — the replacement is not an edit and is not forwarded.
 */
@Composable
fun rememberForwardingTextFieldState(
    initial: String,
    key: Any? = null,
    onEdited: (String) -> Unit,
): TextFieldState {
    val state = remember(key) { TextFieldState(initial) }
    state.ForwardEdits(onEdited)
    return state
}

/**
 * An [InputTransformation] that rewrites the field's text through [transform], inside the field.
 *
 * [transform] must be idempotent (`transform(transform(s)) == transform(s)`) and should shorten or
 * normalise (filters, length caps, case folds) — it is the sanctioned way to restrict input
 * (digit-only fields, code alphabets). The caret lands where the same transform puts the text
 * before it, so a rejected character in the middle of the text leaves the caret exactly where the
 * edit was, not one position past it. An edit the transform rejects entirely is reverted, leaving
 * text and caret untouched.
 */
fun transformedBy(transform: (String) -> String): InputTransformation =
    InputTransformation {
        val raw = asCharSequence().toString()
        val transformed = transform(raw)
        if (transformed != raw) {
            if (transformed == originalText.toString()) {
                revertAllChanges()
            } else {
                val caret = transform(raw.substring(0, selection.end.coerceAtMost(raw.length))).length
                replace(0, length, transformed)
                selection = TextRange(caret.coerceAtMost(transformed.length))
            }
        }
    }

/**
 * An [InputTransformation] that holds the text to [maxLength] characters by dropping overflow from
 * what an edit INSERTS — never by cutting the text already written (see [limitEdit]).
 */
fun limitedTo(maxLength: Int): InputTransformation =
    InputTransformation {
        val previous = originalText.toString()
        val limited =
            limitEdit(
                previous = previous,
                edited = asCharSequence().toString(),
                editedCaret = selection.end,
                maxLength = maxLength,
            )
        if (limited != null) {
            if (limited.text == previous) {
                revertAllChanges()
            } else {
                replace(0, length, limited.text)
                selection = TextRange(limited.caret)
            }
        }
    }

/** [text] with the caret at [caret] — the outcome of [limitEdit]. */
internal data class LimitedEdit(
    val text: String,
    val caret: Int,
)

/**
 * [edited] held to [maxLength] characters, given the field was [previous] and the caret now sits at
 * [editedCaret]. Null means the edit needs no limiting.
 *
 * An edit that shrinks the text, or stays within the limit, passes untouched. An edit that grows it
 * past the limit only loses overflow from what it INSERTED: the text on either side of the edit is
 * never touched, so typing mid-note at the limit cannot eat the note's tail. A paste keeps as much as
 * fits (never splitting a surrogate pair); when nothing of the insertion fits, the edit is refused and
 * [previous] stands.
 */
@Suppress("ReturnCount") // Early exits keep the three outcomes (pass, refuse, clip) flat and readable.
internal fun limitEdit(
    previous: String,
    edited: String,
    editedCaret: Int,
    maxLength: Int,
): LimitedEdit? {
    if (edited.length <= maxLength || edited.length <= previous.length) return null

    // The edit replaced previous[prefix, previous.length - suffix] with edited[prefix, edited.length - suffix].
    val prefix = previous.commonPrefixWith(edited).length
    val suffix =
        previous
            .substring(prefix)
            .commonSuffixWith(edited.substring(prefix))
            .length
    val inserted = edited.substring(prefix, edited.length - suffix)
    val room = maxLength - (prefix + suffix)
    if (room <= 0) return LimitedEdit(previous, editedCaret.coerceAtMost(previous.length))

    var keep = inserted.take(room)
    if (keep.isNotEmpty() && keep.last().isHighSurrogate()) keep = keep.dropLast(1)
    if (keep.isEmpty()) return LimitedEdit(previous, editedCaret.coerceAtMost(previous.length))

    return LimitedEdit(
        text = edited.substring(0, prefix) + keep + edited.substring(edited.length - suffix),
        caret = prefix + keep.length,
    )
}
