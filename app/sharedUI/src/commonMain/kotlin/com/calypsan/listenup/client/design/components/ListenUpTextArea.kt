package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Multi-line text area using the theme's expressive shape system.
 *
 * Uses [MaterialTheme.shapes.medium] for consistent corner radius across the app.
 * Inherits dynamic color support from the theme.
 *
 * Owns its caret locally (see [rememberOwnedTextFieldState]): [value] round-trips asynchronously
 * through the caller, so a stale echo of the user's own keystroke must never clamp the selection.
 *
 * @param value Current text value
 * @param onValueChange Callback when text changes. Callers must echo the propagated string back
 *   through [value] verbatim or not at all — never transformed (see [OwnedTextFieldState])
 * @param label Floating label text
 * @param modifier Optional modifier
 * @param placeholder Hint text shown when empty
 * @param minLines Minimum number of visible lines
 * @param maxLines Maximum number of visible lines
 * @param enabled Whether the field is enabled for input
 * @param isError Whether to show error styling
 * @param supportingText Helper or error text below field
 * @param keyboardOptions Keyboard type and IME action configuration
 * @param keyboardActions Keyboard action handlers
 * @param maxLength When set, an edit that would grow the text past this many characters keeps only
 *   what fits of its insertion (see [limitEdit]); the text around it is never cut. Enforced here,
 *   inside the field, so the caller only ever sees (and echoes) text within the limit
 */
@Composable
fun ListenUpTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    minLines: Int = 3,
    maxLines: Int = 6,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLength: Int? = null,
) {
    val ownedText = rememberOwnedTextFieldState(value)
    OutlinedTextField(
        value = ownedText.fieldValue,
        onValueChange = { typed ->
            val newValue = if (maxLength == null) typed else limitEdit(ownedText.fieldValue, typed, maxLength)
            if (ownedText.edit(newValue)) onValueChange(newValue.text)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        minLines = minLines,
        maxLines = maxLines,
        singleLine = false,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * [edited] held to [maxLength] characters, given the field was [previous].
 *
 * An edit that shrinks the text, or stays within the limit, passes untouched. An edit that grows it
 * past the limit only loses overflow from what it INSERTED: the text on either side of the edit is
 * never touched, so typing mid-note at the limit cannot eat the note's tail. A paste keeps as much as
 * fits (never splitting a surrogate pair); when nothing of the insertion fits, the edit is refused and
 * [previous] stands.
 */
internal fun limitEdit(
    previous: TextFieldValue,
    edited: TextFieldValue,
    maxLength: Int,
): TextFieldValue {
    val old = previous.text
    val new = edited.text
    if (new.length <= maxLength || new.length <= old.length) return edited

    // The edit replaced old[prefix, old.length - suffix] with new[prefix, new.length - suffix].
    val prefix = old.commonPrefixWith(new).length
    val suffix =
        old
            .substring(prefix)
            .commonSuffixWith(new.substring(prefix))
            .length
    val inserted = new.substring(prefix, new.length - suffix)
    val room = maxLength - (prefix + suffix)
    if (room <= 0) return previous

    var keep = inserted.take(room)
    if (keep.isNotEmpty() && keep.last().isHighSurrogate()) keep = keep.dropLast(1)
    if (keep.isEmpty()) return previous

    val text = new.substring(0, prefix) + keep + new.substring(new.length - suffix)
    val caret = prefix + keep.length
    return TextFieldValue(
        text = text,
        selection = TextRange(caret),
        composition =
            edited.composition?.let {
                TextRange(it.start.coerceAtMost(text.length), it.end.coerceAtMost(text.length))
            },
    )
}
