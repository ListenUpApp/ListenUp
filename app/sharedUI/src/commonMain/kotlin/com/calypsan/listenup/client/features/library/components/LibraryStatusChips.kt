package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_status_all
import listenup.composeapp.generated.resources.library_status_chip
import listenup.composeapp.generated.resources.library_status_filters_label
import listenup.composeapp.generated.resources.library_status_finished
import listenup.composeapp.generated.resources.library_status_in_progress
import listenup.composeapp.generated.resources.library_status_not_started
import org.jetbrains.compose.resources.stringResource

/** Status filters with whole-library counts (board `Main`): one row that scrolls and never wraps. */
@Composable
internal fun LibraryStatusChips(
    selected: BookStatusFilter,
    counts: BookStatusCounts,
    onSelect: (BookStatusFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupLabel = stringResource(Res.string.library_status_filters_label)
    LazyRow(
        modifier = modifier.semantics { contentDescription = groupLabel },
        contentPadding = PaddingValues(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(BookStatusFilter.entries) { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(Res.string.library_status_chip, filter.label(), counts.countFor(filter))) },
                leadingIcon =
                    if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
internal fun BookStatusFilter.label(): String =
    stringResource(
        when (this) {
            BookStatusFilter.ALL -> Res.string.library_status_all
            BookStatusFilter.IN_PROGRESS -> Res.string.library_status_in_progress
            BookStatusFilter.NOT_STARTED -> Res.string.library_status_not_started
            BookStatusFilter.FINISHED -> Res.string.library_status_finished
        },
    )
