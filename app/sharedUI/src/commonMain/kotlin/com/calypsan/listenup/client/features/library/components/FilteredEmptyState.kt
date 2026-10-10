package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_filtered_empty_finished
import listenup.composeapp.generated.resources.library_filtered_empty_in_progress
import listenup.composeapp.generated.resources.library_filtered_empty_not_started
import listenup.composeapp.generated.resources.library_show_all_books
import org.jetbrains.compose.resources.stringResource

/**
 * The Books view when the library has books but the active status [filter] matches none of them.
 * Never the "your library is empty" state: that would tell a reader their books are gone.
 */
@Composable
internal fun FilteredEmptyState(
    filter: BookStatusFilter,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy =
        when (filter) {
            BookStatusFilter.IN_PROGRESS -> Res.string.library_filtered_empty_in_progress
            BookStatusFilter.NOT_STARTED -> Res.string.library_filtered_empty_not_started
            // ALL never reaches here: a library with books always matches All.
            BookStatusFilter.FINISHED, BookStatusFilter.ALL -> Res.string.library_filtered_empty_finished
        }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(copy),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onShowAll) { Text(stringResource(Res.string.library_show_all_books)) }
    }
}
