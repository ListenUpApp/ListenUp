package com.calypsan.listenup.client.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonMatchEvent
import com.calypsan.listenup.client.presentation.match.PersonMatchViewModel
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_review_reloaded_person
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Match details for one person: As author | As narrator, Find, Review (photo and biography chosen apart), and
 * one Apply. One ViewModel holds the session, so going back to the results never searches again. On
 * [PersonMatchEvent.Applied] the host returns to the contributor page ([onApplied]), which shows the receipt;
 * [onEditByHand] opens the contributor editor when no source has a profile; [onBack] leaves without applying.
 */
@Composable
fun PersonMatchRoute(
    contributorId: String,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    onEditByHand: () -> Unit,
    viewModel: PersonMatchViewModel = koinViewModel(parameters = { parametersOf(contributorId) }),
) {
    val findState by viewModel.findState.collectAsStateWithLifecycle()
    val reviewState by viewModel.reviewState.collectAsStateWithLifecycle()
    val userRepository: UserRepository = koinInject()
    val viewer by remember(userRepository) { userRepository.observeCurrentUser() }.collectAsStateWithLifecycle(null)
    val snackbarHostState = LocalSnackbarHostState.current
    val reloaded = stringResource(Res.string.match_review_reloaded_person)
    val currentOnApplied by rememberUpdatedState(onApplied)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PersonMatchEvent.Applied -> currentOnApplied()
                PersonMatchEvent.ReviewReloaded -> snackbarHostState.showSnackbar(reloaded)
            }
        }
    }

    PersonMatchScreen(
        contributorId = contributorId,
        findState = findState,
        reviewState = reviewState,
        viewerId = viewer?.idString,
        actions = remember(viewModel) { ViewModelPersonMatchActions(viewModel) },
        onBack = onBack,
        onEditByHand = onEditByHand,
    )
}

/**
 * A person's Match details from fixed state, laid out by [MatchPanes]: the people in the results pane, Review
 * beside them from the two-pane width or pushed over them below it.
 */
@Composable
fun PersonMatchScreen(
    contributorId: String,
    findState: PersonFindUiState,
    reviewState: PersonReviewUiState,
    viewerId: String?,
    actions: PersonMatchActions,
    onBack: () -> Unit,
    onEditByHand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MatchPanes(
        reviewOpen = reviewState !is PersonReviewUiState.NoneChosen,
        onTwoPaneChanged = actions::useTwoPane,
        onBackToResults = actions::backToResults,
        modifier = modifier,
        listPane = { isTwoPane ->
            PersonFindPane(
                state = findState,
                highlightPicked = isTwoPane,
                actions = actions,
                onBack = onBack,
                onEditByHand = onEditByHand,
            )
        },
        detailPane = { isTwoPane ->
            PersonReviewPane(
                state = reviewState,
                contributorId = contributorId,
                header = findState.header,
                viewerId = viewerId,
                isTwoPane = isTwoPane,
                actions = actions,
            )
        },
    )
}
