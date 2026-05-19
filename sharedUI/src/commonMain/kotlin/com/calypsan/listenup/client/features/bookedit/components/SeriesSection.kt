package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.presentation.bookedit.EditableSeries
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_edit_add_trimmedquery
import listenup.composeapp.generated.resources.book_edit_showing_offline_results

/**
 * Series editing section with search and sequence editing.
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesSection(
    series: List<EditableSeries>,
    searchQuery: String,
    searchResults: List<SeriesSearchResult>,
    isLoading: Boolean,
    isOffline: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSeriesSelected: (SeriesSearchResult) -> Unit,
    onSeriesEntered: (String) -> Unit,
    onSequenceChange: (EditableSeries, String) -> Unit,
    onRemoveSeries: (EditableSeries) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Existing series with sequence editing
        series.forEach { s ->
            SeriesChipWithSequence(
                series = s,
                onSequenceChange = { sequence -> onSequenceChange(s, sequence) },
                onRemove = { onRemoveSeries(s) },
            )
        }

        // Offline indicator
        if (isOffline && searchResults.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.book_edit_showing_offline_results),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Search field
        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            results = searchResults,
            onResultSelected = { result -> onSeriesSelected(result) },
            onSubmit = { query ->
                val trimmed = query.trim()
                if (trimmed.isNotEmpty()) {
                    val topResult = searchResults.firstOrNull()
                    if (topResult != null) {
                        onSeriesSelected(topResult)
                    } else if (trimmed.length >= 2) {
                        onSeriesEntered(trimmed)
                    }
                }
            },
            resultContent = { result ->
                AutocompleteResultItem(
                    name = result.name,
                    subtitle =
                        if (result.bookCount > 0) {
                            "${result.bookCount} ${if (result.bookCount == 1) "book" else "books"}"
                        } else {
                            null
                        },
                    onClick = { onSeriesSelected(result) },
                )
            },
            placeholder = "Add series...",
            isLoading = isLoading,
        )

        // Add new chip
        val trimmedQuery = searchQuery.trim()
        val hasExactMatch =
            searchResults.any {
                it.name.equals(trimmedQuery, ignoreCase = true)
            }
        if (trimmedQuery.length >= 2 && !isLoading && !hasExactMatch) {
            AssistChip(
                onClick = { onSeriesEntered(trimmedQuery) },
                label = { Text(stringResource(Res.string.book_edit_add_trimmedquery, trimmedQuery)) },
                leadingIcon = {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                },
            )
        }
    }
}

@Composable
private fun SeriesChipWithSequence(
    series: EditableSeries,
    onSequenceChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InputChip(
            selected = false,
            onClick = { },
            label = { Text(series.name) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove ${series.name}",
                    modifier =
                        Modifier
                            .size(InputChipDefaults.AvatarSize)
                            .clickable { onRemove() },
                )
            },
            modifier =
                Modifier
                    .weight(1f)
                    .height(56.dp),
        )

        OutlinedTextField(
            value = series.sequence ?: "",
            onValueChange = onSequenceChange,
            label = { Text("#") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f),
        )
    }
}
