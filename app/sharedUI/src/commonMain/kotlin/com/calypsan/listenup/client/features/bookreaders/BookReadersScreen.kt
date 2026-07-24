@file:OptIn(kotlin.time.ExperimentalTime::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.calypsan.listenup.client.features.bookreaders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.bookdetail.components.ReaderRow
import com.calypsan.listenup.client.features.bookdetail.components.toReaderRows
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_readers
import listenup.composeapp.generated.resources.book_detail_readers_empty
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock

/** Caps the readable column width so the list stays comfortable on tablets and desktop. */
private val ReadableWidth = 560.dp

/**
 * Full "See all" Readers screen — the uncapped list behind the Book Detail Readers section's
 * "See all" affordance.
 *
 * Mirrors [com.calypsan.listenup.client.features.bookdetail.components.BookReadersSection]'s
 * flatten → [com.calypsan.listenup.client.features.bookdetail.components.ReaderRowUi] mapping via
 * the shared `toReaderRows` helper, but renders *every* row instead of the section's five. Each row
 * is the same [ReaderRow] composable, so the two surfaces never drift.
 *
 * @param bookId The book whose readers to list; scopes the [BookReadersViewModel].
 * @param onBack Pops back to the originating Book Detail screen.
 * @param onUserClick Navigates to a reader's profile (same target as the section's row click).
 * @param viewModel The readers ViewModel, scoped to [bookId].
 */
@Composable
fun BookReadersScreen(
    bookId: String,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: BookReadersViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.book_detail_readers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        BookReadersBody(
            state = state,
            innerPadding = innerPadding,
            onUserClick = onUserClick,
        )
    }
}

/**
 * Stateless body for [BookReadersScreen]. Renders the full reader list on
 * [BookReadersUiState.Data], a centered loading indicator while the first emission is in flight, and
 * a centered empty message for [BookReadersUiState.NoReaders] / [BookReadersUiState.Error] (the
 * Readers list is non-critical, so an error reads the same as "nobody yet").
 */
@Composable
private fun BookReadersBody(
    state: BookReadersUiState,
    innerPadding: PaddingValues,
    onUserClick: (String) -> Unit,
) {
    when (state) {
        is BookReadersUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        is BookReadersUiState.NoReaders, is BookReadersUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.book_detail_readers_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        is BookReadersUiState.Data -> {
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val rows = state.readers.readers.toReaderRows(nowMs)

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = Spacing.screenMargin, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(
                    items = rows,
                    key = { row -> "${row.userId}:${row.isReading}:${row.finishedWhen}" },
                ) { row ->
                    ReaderRow(
                        reader = row,
                        onUserClick = onUserClick,
                        modifier = Modifier.fillMaxWidth().widthIn(max = ReadableWidth),
                    )
                }
            }
        }
    }
}
