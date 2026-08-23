package com.calypsan.listenup.client.features.bookedit.components

import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.MIN_SEARCH_QUERY_LENGTH
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AutocompleteEmptyResultsHint
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.presentation.bookedit.EditableSeries
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_remove_name
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
                    } else if (trimmed.length >= MIN_SEARCH_QUERY_LENGTH) {
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
            emptyResultsContent = { AutocompleteEmptyResultsHint(query = searchQuery) },
        )

        // Add new chip
        val trimmedQuery = searchQuery.trim()
        val hasExactMatch =
            searchResults.any {
                it.name.equals(trimmedQuery, ignoreCase = true)
            }
        if (trimmedQuery.length >= MIN_SEARCH_QUERY_LENGTH && !isLoading && !hasExactMatch) {
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
    val haptics = LocalHaptics.current
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
                    contentDescription = stringResource(Res.string.common_remove_name, series.name),
                    modifier =
                        Modifier
                            .size(InputChipDefaults.AvatarSize)
                            .clickable {
                                haptics.press()
                                onRemove()
                            },
                )
            },
            modifier =
                Modifier
                    .weight(1f)
                    .height(56.dp),
        )

        ListenUpTextField(
            value = series.sequence ?: "",
            onValueChange = onSequenceChange,
            label = "#",
            // Decimal, not Number: half-numbered entries ("1.5") are ordinary in book series, and a
            // plain number pad hides the separator they need. Number is right for the publish year
            // in PublishingSection, which genuinely has no fractional part — this field was simply
            // never given a type, so it opened the full alphabetic keyboard for a number.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            // The keyboard is a hint, not a constraint — a hardware keyboard, a paste, or a
            // switched IME can still deliver letters. The transform is what actually holds the
            // shape, and it runs inside the field's own text ledger so a rejected character never
            // reaches the caller and never moves the caret.
            transform = ::keepDecimalCharacters,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Keeps [raw] to the shape of a series position: digits, and at most one decimal point.
 *
 * Deliberately permissive about *incomplete* input. `"1."` is not a number and never will be on its
 * own, but it is what every user types on the way to `"1.5"`, so it has to survive — which is also
 * why [com.calypsan.listenup.client.domain.model.EditableSeries] holds text rather than a `Double`
 * while the screen is open. Rejecting a keystroke here only ever removes a character the value
 * could not have contained; it never rewrites what the user already has.
 */
internal fun keepDecimalCharacters(raw: String): String {
    val builder = StringBuilder(raw.length)
    var seenPoint = false
    for (char in raw) {
        when {
            char.isDigit() -> {
                builder.append(char)
            }

            char == '.' && !seenPoint -> {
                seenPoint = true
                builder.append(char)
            }
        }
    }
    return builder.toString()
}
