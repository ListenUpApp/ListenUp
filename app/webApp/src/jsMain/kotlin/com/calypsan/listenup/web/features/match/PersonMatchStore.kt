package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonMatchEvent
import com.calypsan.listenup.client.presentation.match.PersonMatchViewModel
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * An open Match details session for one person: Find and Review state, the one-shot outcomes, and one callback
 * per [PersonMatchViewModel] action, under the ViewModel's own names — the same shape as [BookMatchSession].
 */
@Suppress("LongParameterList")
class PersonMatchSession(
    val findState: StateFlow<PersonFindUiState>,
    val reviewState: StateFlow<PersonReviewUiState>,
    val events: Flow<PersonMatchEvent>,
    val search: (String) -> Unit,
    val retry: () -> Unit,
    val pick: (PersonCandidateKey) -> Unit,
    val backToResults: () -> Unit,
    val useTwoPane: (Boolean) -> Unit,
    val setPhotoTicked: (Boolean) -> Unit,
    val choosePhoto: (ImageChoice) -> Unit,
    val setBiographyTicked: (Boolean) -> Unit,
    val chooseBiographySource: (FieldChoice) -> Unit,
    val apply: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the page gets its session for a contributor. */
typealias OpenPersonMatch = (contributorId: String) -> PersonMatchSession

/** The production source: [PersonMatchViewModel] for one contributor. It starts Find itself. */
fun graphPersonMatch(koin: Koin): OpenPersonMatch =
    { contributorId ->
        val viewModel = koin.get<PersonMatchViewModel> { parametersOf(contributorId) }
        val store = ViewModelStore().apply { put(contributorId, viewModel) }
        PersonMatchSession(
            findState = viewModel.findState,
            reviewState = viewModel.reviewState,
            events = viewModel.events,
            search = viewModel::search,
            retry = viewModel::retry,
            pick = viewModel::pick,
            backToResults = viewModel::backToResults,
            useTwoPane = viewModel::useTwoPane,
            setPhotoTicked = viewModel::setPhotoTicked,
            choosePhoto = viewModel::choosePhoto,
            setBiographyTicked = viewModel::setBiographyTicked,
            chooseBiographySource = viewModel::chooseBiographySource,
            apply = viewModel::apply,
            close = store::clear,
        )
    }
