package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.MetadataViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route for the book metadata search screen.
 *
 * Loads the book, initializes the wizard, and auto-searches. When a result is
 * clicked, forwards the ASIN to [onResultSelected] so the host can navigate to
 * the match preview.
 */
@Composable
fun MetadataSearchRoute(
    bookId: String,
    onResultSelected: (asin: String) -> Unit,
    onBack: () -> Unit,
    viewModel: MetadataViewModel = koinViewModel(),
) {
    val bookRepository: BookRepository = koinInject()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) {
        val book = bookRepository.getBookDetail(bookId) ?: return@LaunchedEffect
        viewModel.initForBook(
            bookId = bookId,
            title = book.title,
            author = book.authors.firstOrNull()?.name ?: "",
            asin = book.asin,
        )
        if (book.asin != null) {
            viewModel.updateQuery(book.asin!!)
        }
        viewModel.search()
    }

    when (val current = state) {
        is MetadataUiState.Search -> {
            MetadataSearchScreen(
                state = current,
                onQueryChange = viewModel::updateQuery,
                onSearch = viewModel::search,
                onRegionSelected = viewModel::changeRegion,
                onResultClick = { result ->
                    viewModel.selectMatch(result)
                    onResultSelected(result.asin)
                },
                onBack = onBack,
            )
        }

        is MetadataUiState.Idle,
        is MetadataUiState.Preview,
        -> {
            // Idle: initForBook hasn't fired yet — show a spinner.
            // Preview: the user just picked a match; navigation is in flight.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (current is MetadataUiState.Idle) {
                    ListenUpLoadingIndicator()
                } else {
                    Text("Loading match…")
                }
            }
        }
    }
}
