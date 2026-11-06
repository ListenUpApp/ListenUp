package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/**
 * Multi-line text area using the theme's expressive shape system.
 *
 * Uses [MaterialTheme.shapes.medium] for consistent corner radius across the app.
 * Inherits dynamic color support from the theme.
 *
 * The caller owns the [state]; the field owns the text and the caret, so nothing round-trips
 * through a ViewModel while the person is typing.
 *
 * @param state The text and selection; read edits with [ForwardEdits]
 * @param label Floating label text
 * @param modifier Optional modifier
 * @param placeholder Hint text shown when empty
 * @param minLines Minimum number of visible lines
 * @param maxLines Maximum number of visible lines
 * @param enabled Whether the field is enabled for input
 * @param isError Whether to show error styling
 * @param supportingText Helper or error text below field
 * @param keyboardOptions Keyboard type and IME action configuration
 * @param onKeyboardAction Called when the IME action key is pressed; null keeps the default action
 * @param maxLength When set, an edit that would grow the text past this many characters keeps only
 *   what fits of its insertion (see [limitedTo]); the text around it is never cut
 */
@Composable
fun ListenUpTextArea(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    minLines: Int = 3,
    maxLines: Int = 6,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
    onKeyboardAction: (() -> Unit)? = null,
    maxLength: Int? = null,
) {
    OutlinedTextField(
        state = state,
        inputTransformation = maxLength?.let(::limitedTo),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction?.let { action -> KeyboardActionHandler { action() } },
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}
