package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MetadataSearchResult
import com.calypsan.listenup.client.presentation.metadata.AudibleRegion
import com.calypsan.listenup.client.presentation.metadata.MetadataEvent
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.MetadataViewModel
import com.calypsan.listenup.client.presentation.metadata.PreviewLoadState
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.metadata_audible_catalog_try_a_different
import listenup.composeapp.generated.resources.metadata_book_not_found_on_audible
import listenup.composeapp.generated.resources.common_back

/**
 * Route for the match preview screen.
 *
 * If the shared ViewModel hasn't already loaded a preview for this ASIN (e.g.
 * the user deep-linked here or the flow was reset), re-initialize and trigger
 * [MetadataViewModel.selectMatch]. [MetadataEvent.MatchApplied] drives
 * navigation away.
 */
@Composable
fun MatchPreviewRoute(
    bookId: String,
    asin: String,
    onBack: () -> Unit,
    onApplySuccess: () -> Unit,
    metadataViewModel: MetadataViewModel = koinViewModel(),
) {
    val bookRepository: BookRepository = koinInject()
    val state by metadataViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(bookId, asin) {
        val current = state
        val alreadyOnThisMatch =
            current is MetadataUiState.Preview && current.match.asin == asin
        if (!alreadyOnThisMatch) {
            metadataViewModel.initForBook(bookId = bookId, title = "", author = "")
            metadataViewModel.selectMatch(MetadataSearchResult(asin = asin, title = ""))
        }
    }

    LaunchedEffect(metadataViewModel) {
        metadataViewModel.events.collect { event ->
            when (event) {
                MetadataEvent.MatchApplied -> onApplySuccess()
            }
        }
    }

    val currentBook by produceState<BookDetail?>(initialValue = null, key1 = bookId) {
        value = bookRepository.getBookDetail(bookId)
    }

    val preview = state as? MetadataUiState.Preview
    when {
        currentBook == null || preview == null || preview.loadState is PreviewLoadState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ListenUpLoadingIndicator()
            }
        }

        preview.loadState is PreviewLoadState.Failed -> {
            NotFoundErrorScreen(
                selectedRegion = preview.region,
                onRegionSelected = metadataViewModel::changeRegion,
                onBack = onBack,
            )
        }

        else -> {
            val ready = preview.loadState as PreviewLoadState.Ready
            MatchPreviewScreen(
                currentBook = currentBook!!,
                newMetadata = ready.preview,
                selections = ready.selections,
                isApplying = ready.isApplying,
                applyError = ready.applyError,
                previewNotFound = ready.previewNotFound,
                selectedRegion = preview.region,
                coverOptions = ready.coverOptions,
                isLoadingCovers = ready.isLoadingCovers,
                selectedCoverUrl = ready.selectedCoverUrl,
                onSelectCover = metadataViewModel::selectCover,
                onRegionSelected = metadataViewModel::changeRegion,
                onToggleField = metadataViewModel::toggleField,
                onToggleAuthor = metadataViewModel::toggleAuthor,
                onToggleNarrator = metadataViewModel::toggleNarrator,
                onToggleSeries = metadataViewModel::toggleSeries,
                onToggleGenre = metadataViewModel::toggleGenre,
                onApply = metadataViewModel::applyMatch,
                onBack = onBack,
            )
        }
    }
}

/**
 * Error screen when the book cannot be found on Audible.
 * Offers region selection to try different Audible markets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotFoundErrorScreen(
    selectedRegion: AudibleRegion,
    onRegionSelected: (AudibleRegion) -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.metadata_book_not_found_on_audible),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    "This book couldn't be found in the ${selectedRegion.displayName} " +
                        stringResource(Res.string.metadata_audible_catalog_try_a_different),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudibleRegion.entries.forEach { region ->
                    FilterChip(
                        selected = region == selectedRegion,
                        onClick = { onRegionSelected(region) },
                        label = { Text(region.displayName) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onBack) {
                Text(stringResource(Res.string.common_back))
            }
        }
    }
}
