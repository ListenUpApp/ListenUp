package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_clear_search

/**
 * Search text field with leading search icon and trailing clear/loading indicator.
 *
 * Uses [MaterialTheme.shapes.medium] for consistent corner radius.
 * Handles Enter key for search submission.
 *
 * Owns its caret locally (see [rememberOwnedTextFieldState]): [value] round-trips asynchronously
 * through the caller, so a stale echo of the user's own keystroke must never clamp the selection.
 *
 * @param value Current search text
 * @param onValueChange Callback when text changes. Callers must echo the propagated string back
 *   through [value] verbatim or not at all — never transformed (see [OwnedTextFieldState])
 * @param onSubmit Callback when Enter key is pressed
 * @param placeholder Hint text shown when empty
 * @param modifier Optional modifier
 * @param enabled Whether the field is enabled for input
 * @param isLoading Whether to show loading indicator instead of clear button
 * @param onClear Callback when clear button is clicked (if null, no clear button shown)
 */
@Composable
fun ListenUpSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClear: (() -> Unit)? = null,
) {
    val ownedText = rememberOwnedTextFieldState(value)
    OutlinedTextField(
        value = ownedText.fieldValue,
        onValueChange = { newValue ->
            if (ownedText.edit(newValue)) onValueChange(newValue.text)
        },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        singleLine = true,
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

                // Gate on the local text — it is what the field renders, so the clear affordance
                // never disagrees with what the user sees while an echo is in flight.
                ownedText.fieldValue.text.isNotEmpty() && onClear != null -> {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(Res.string.common_clear_search),
                        )
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier.fillMaxWidth(),
    )
}
