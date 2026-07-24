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
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataEvent
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route for the contributor metadata preview screen.
 *
 * The Nav3 per-entry ViewModel store gives this route its own
 * [ContributorMetadataViewModel], so the carried [region] is applied BEFORE selecting the
 * match — the first profile fetch hits the regional catalog the hit was found in instead of
 * re-defaulting to US (profiles are region-localized). Mirrors MatchPreviewRoute.
 * [ContributorMetadataEvent.MetadataApplied] drives navigation away.
 */
@Composable
fun ContributorMetadataPreviewRoute(
    contributorId: String,
    asin: String,
    region: MetadataLocale,
    onApplySuccess: () -> Unit,
    onChangeMatch: () -> Unit,
    onBack: () -> Unit,
    viewModel: ContributorMetadataViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(contributorId, asin, region) {
        val current = viewModel.state.value
        val alreadyOnThisMatch =
            current is ContributorMetadataUiState.Preview &&
                current.match.asin == asin &&
                current.region == region
        if (!alreadyOnThisMatch) {
            // Region BEFORE select: changeRegion on a blank-query Search is a no-op search,
            // and selectAsin then fetches in the carried region (see MatchPreviewRoute).
            viewModel.init(contributorId)
            viewModel.changeRegion(region)
            viewModel.selectAsin(asin)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ContributorMetadataEvent.MetadataApplied -> onApplySuccess()
            }
        }
    }

    val preview = state as? ContributorMetadataUiState.Preview
    if (preview == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ListenUpLoadingIndicator()
        }
    } else {
        ContributorMetadataPreviewScreen(
            state = preview,
            onRegionSelected = viewModel::changeRegion,
            onApply = viewModel::apply,
            onChangeMatch = onChangeMatch,
            onBack = onBack,
        )
    }
}
