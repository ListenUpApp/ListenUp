@file:Suppress("MagicNumber", "StringLiteralDuplication")

package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_add_name

/**
 * Search field with autocomplete dropdown results.
 *
 * Combines [ListenUpSearchField] with a dropdown card showing search results.
 * Results are displayed below the search field when available.
 *
 * @param T Type of result items
 * @param value Current search text
 * @param onValueChange Callback when text changes
 * @param results List of search results to display
 * @param onResultSelected Callback when a result is clicked
 * @param onSubmit Callback when Enter is pressed (receives current text)
 * @param resultContent Composable to render each result item
 * @param placeholder Hint text for search field
 * @param modifier Optional modifier
 * @param isLoading Whether search is in progress
 * @param emptyResultsContent Optional content to show when results are empty but query is valid
 */
@Composable
@Suppress("UnusedParameter")
fun <T> ListenUpAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    results: List<T>,
    onResultSelected: (T) -> Unit,
    onSubmit: (String) -> Unit,
    resultContent: @Composable (T) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    emptyResultsContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ListenUpSearchField(
            value = value,
            onValueChange = onValueChange,
            onSubmit = { onSubmit(value) },
            placeholder = placeholder,
            isLoading = isLoading,
            onClear = { onValueChange("") },
        )

        // Show results dropdown when there are results
        if (results.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    results.forEach { result ->
                        resultContent(result)
                    }
                }
            }
        } else if (value.length >= 2 && !isLoading && emptyResultsContent != null) {
            // Show empty state when query is valid but no results
            emptyResultsContent()
        }
    }
}

/**
 * Standard autocomplete result item with icon, name, and optional subtitle.
 *
 * @param name Primary text to display
 * @param subtitle Optional secondary text (e.g., book count)
 * @param onClick Callback when item is clicked
 * @param modifier Optional modifier
 * @param leadingIcon Leading icon composable
 */
@Composable
fun AutocompleteResultItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isLoading: Boolean = false,
    leadingIcon: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    },
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable {
                    keyboardController?.hide()
                    onClick()
                }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(Res.string.common_add_name, name),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
