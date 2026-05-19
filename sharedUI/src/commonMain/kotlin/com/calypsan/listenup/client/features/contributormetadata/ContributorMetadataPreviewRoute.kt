package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route for the contributor metadata preview screen.
 *
 * Handles ViewModel state and navigation after apply.
 */
@Composable
fun ContributorMetadataPreviewRoute(
    contributorId: String,
    asin: String,
    onApplySuccess: () -> Unit,
    onChangeMatch: () -> Unit,
    onBack: () -> Unit,
    viewModel: ContributorMetadataViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Initialize if needed and load profile
    LaunchedEffect(contributorId, asin) {
        if (state.contributorId != contributorId) {
            viewModel.init(contributorId)
        }
        // Load the profile for the selected ASIN
        if (state.selectedCandidate?.asin != asin || state.previewProfile == null) {
            viewModel.loadProfileByAsin(asin)
        }
    }

    // Handle apply success
    LaunchedEffect(state.applySuccess) {
        if (state.applySuccess) {
            onApplySuccess()
        }
    }

    ContributorMetadataPreviewScreen(
        state = state,
        onToggleField = viewModel::toggleField,
        onApply = viewModel::apply,
        onChangeMatch = onChangeMatch,
        onBack = onBack,
    )
}
