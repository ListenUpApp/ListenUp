package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route for the contributor metadata search screen.
 *
 * Initializes the per-entry ViewModel once for this contributor and forwards the
 * currently selected region alongside the tapped ASIN, so the preview route can
 * fetch the profile from the same regional catalog the hit was found in.
 */
@Composable
fun ContributorMetadataSearchRoute(
    contributorId: String,
    onCandidateSelected: (asin: String, region: MetadataLocale) -> Unit,
    onBack: () -> Unit,
    viewModel: ContributorMetadataViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(contributorId) {
        val current = viewModel.state.value
        val alreadyInitialized =
            when (current) {
                is ContributorMetadataUiState.Search -> current.context.contributorId == contributorId
                is ContributorMetadataUiState.Preview -> current.context.contributorId == contributorId
                is ContributorMetadataUiState.Idle -> false
            }
        if (!alreadyInitialized) viewModel.init(contributorId)
    }

    when (val current = state) {
        is ContributorMetadataUiState.Search -> {
            ContributorMetadataSearchScreen(
                state = current,
                onQueryChange = viewModel::updateQuery,
                onSearch = viewModel::search,
                onRegionSelected = viewModel::changeRegion,
                onResultClick = { result -> onCandidateSelected(result.asin, current.region) },
                onBack = onBack,
            )
        }

        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ListenUpLoadingIndicator()
            }
        }
    }
}
