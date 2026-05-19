package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route for the contributor metadata search screen.
 *
 * Handles ViewModel initialization and navigation.
 */
@Composable
fun ContributorMetadataSearchRoute(
    contributorId: String,
    onCandidateSelected: (asin: String) -> Unit,
    onBack: () -> Unit,
    viewModel: ContributorMetadataViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Initialize ViewModel with contributor ID
    LaunchedEffect(contributorId) {
        viewModel.init(contributorId)
    }

    ContributorMetadataSearchScreen(
        state = state,
        onQueryChange = viewModel::updateQuery,
        onSearch = viewModel::search,
        onRegionSelected = viewModel::changeRegion,
        onResultClick = { result ->
            onCandidateSelected(result.asin)
        },
        onBack = onBack,
    )
}
