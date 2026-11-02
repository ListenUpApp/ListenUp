package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_clear_search

/**
 * Search text field with leading search icon and trailing clear/loading indicator.
 *
 * Uses [MaterialTheme.shapes.medium] for consistent corner radius.
 * Handles Enter key for search submission.
 *
 * The caller owns the [state]; the field owns the text and the caret, so nothing round-trips
 * through a ViewModel while the person is typing.
 *
 * @param state The search text and selection; read edits with [ForwardEdits]
 * @param onSubmit Callback when Enter key is pressed
 * @param placeholder Hint text shown when empty
 * @param label What the field searches ("Search Hardcover"), when the screen doesn't already say. Unlike
 *   [placeholder] it stays once the field holds a search — floated above the text — so neither TalkBack nor
 *   a sighted reader loses what the field is for.
 * @param modifier Optional modifier
 * @param enabled Whether the field is enabled for input
 * @param isLoading Whether to show loading indicator instead of clear button
 * @param clearable Whether a clear button is offered while the field holds text; it empties [state]
 */
@Composable
fun ListenUpSearchField(
    state: TextFieldState,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    clearable: Boolean = false,
    label: String? = null,
) {
    val haptics = LocalHaptics.current
    OutlinedTextField(
        state = state,
        placeholder = { Text(placeholder) },
        label = label?.let { { Text(it) } },
        enabled = enabled,
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = MaterialTheme.shapes.medium,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            when {
                isLoading -> {
                    ListenUpLoadingIndicator(size = 20.dp)
                }

                state.text.isNotEmpty() && clearable -> {
                    IconButton(
                        onClick = {
                            haptics.press()
                            state.clearText()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(Res.string.common_clear_search),
                        )
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = KeyboardActionHandler { onSubmit() },
        modifier = modifier.fillMaxWidth(),
    )
}
