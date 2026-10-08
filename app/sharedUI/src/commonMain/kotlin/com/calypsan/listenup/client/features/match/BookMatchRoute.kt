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
import com.calypsan.listenup.client.presentation.match.BookMatchEvent
import com.calypsan.listenup.client.presentation.match.BookMatchViewModel
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_review_reloaded
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Match details for one book: Find, an optional Compare, Review, and one Apply. One ViewModel holds the whole
 * session, so going back to the results never searches again. On [BookMatchEvent.Applied] the host returns
 * to Book Detail ([onApplied]), which shows the receipt; [onBack] leaves without applying.
 */
@Composable
fun BookMatchRoute(
    bookId: String,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    viewModel: BookMatchViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val findState by viewModel.findState.collectAsStateWithLifecycle()
    val reviewState by viewModel.reviewState.collectAsStateWithLifecycle()
    val userRepository: UserRepository = koinInject()
    val viewer by remember(userRepository) { userRepository.observeCurrentUser() }.collectAsStateWithLifecycle(null)
    val snackbarHostState = LocalSnackbarHostState.current
    val reloaded = stringResource(Res.string.match_review_reloaded)
    val currentOnApplied by rememberUpdatedState(onApplied)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BookMatchEvent.Applied -> currentOnApplied()
                BookMatchEvent.ReviewReloaded -> snackbarHostState.showSnackbar(reloaded)
            }
        }
    }

    BookMatchScreen(
        bookId = bookId,
        findState = findState,
        reviewState = reviewState,
        viewerId = viewer?.idString,
        actions = remember(viewModel) { ViewModelMatchActions(viewModel) },
        onBack = onBack,
    )
}

/**
 * Match details for a book from fixed state, laid out by [MatchPanes]: Find in the results pane, Review beside
 * it or pushed over it.
 */
@Composable
fun BookMatchScreen(
    bookId: String,
    findState: FindUiState,
    reviewState: ReviewUiState,
    viewerId: String?,
    actions: BookMatchActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MatchPanes(
        reviewOpen = reviewState !is ReviewUiState.NoneChosen,
        onTwoPaneChanged = actions::useTwoPane,
        onBackToResults = actions::backToResults,
        modifier = modifier,
        listPane = { isTwoPane ->
            FindPane(
                state = findState,
                bookId = bookId,
                highlightPicked = isTwoPane,
                actions = actions,
                onBack = onBack,
            )
        },
        detailPane = { isTwoPane ->
            ReviewPane(
                state = reviewState,
                bookId = bookId,
                bookTitle = findState.yourCopy?.title,
                viewerId = viewerId,
                isTwoPane = isTwoPane,
                actions = actions,
            )
        },
    )
}
