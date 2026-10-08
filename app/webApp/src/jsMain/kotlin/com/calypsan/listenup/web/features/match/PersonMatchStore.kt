package com.calypsan.listenup.web.features.match

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonMatchEvent
import com.calypsan.listenup.client.presentation.match.PersonMatchViewModel
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    val close: () -> Unit,
)

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

/**
 * A session over flows a spec owns, recording what it is asked to do. The gestures do not move the state on their
 * own — a spec that wants Review after a pick sets [reviewState] itself, as the server would.
 */
@Suppress("LongParameterList")
fun fixedPersonMatch(
    findState: StateFlow<PersonFindUiState>,
    reviewState: StateFlow<PersonReviewUiState> = MutableStateFlow(PersonReviewUiState.NoneChosen),
    events: Flow<PersonMatchEvent> = emptyFlow(),
    search: (String) -> Unit = {},
    retry: () -> Unit = {},
    pick: (PersonCandidateKey) -> Unit = {},
    backToResults: () -> Unit = {},
    useTwoPane: (Boolean) -> Unit = {},
    setPhotoTicked: (Boolean) -> Unit = {},
    choosePhoto: (ImageChoice) -> Unit = {},
    setBiographyTicked: (Boolean) -> Unit = {},
    chooseBiographySource: (FieldChoice) -> Unit = {},
    apply: () -> Unit = {},
): PersonMatchSession =
    PersonMatchSession(
        findState = findState,
        reviewState = reviewState,
        events = events,
        search = search,
        retry = retry,
        pick = pick,
        backToResults = backToResults,
        useTwoPane = useTwoPane,
        setPhotoTicked = setPhotoTicked,
        choosePhoto = choosePhoto,
        setBiographyTicked = setBiographyTicked,
        chooseBiographySource = chooseBiographySource,
        apply = apply,
        close = {},
    )
