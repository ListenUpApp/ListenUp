package com.calypsan.listenup.client.features.match

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.LocalSnackbarHostState
import com.calypsan.listenup.client.design.TwoPaneMinWidth
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
 * The Match details layout from fixed state: one pane on compact and medium widths (Review pushes over the
 * results, with predictive back), results | review side by side from [TwoPaneMinWidth]. The mode is reported
 * through [BookMatchActions.useTwoPane] whenever it changes, so two panes open the best Strong match at once.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
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
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTwoPane = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())
    val directive =
        remember(adaptiveInfo, isTwoPane) {
            calculatePaneScaffoldDirective(adaptiveInfo).copy(maxHorizontalPartitions = if (isTwoPane) 2 else 1)
        }
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(scaffoldDirective = directive)
    val reviewOpen = reviewState !is ReviewUiState.NoneChosen
    val currentReviewOpen by rememberUpdatedState(reviewOpen)
    val currentTwoPane by rememberUpdatedState(isTwoPane)

    LaunchedEffect(isTwoPane) { actions.useTwoPane(isTwoPane) }

    // The ViewModel leads: a pick opens Review, Back to results closes it.
    LaunchedEffect(reviewOpen) {
        val onReview = navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail
        if (reviewOpen && !onReview) navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        if (!reviewOpen && onReview && navigator.canNavigateBack()) navigator.navigateBack()
    }
    // A system back (predictive on Android) that took one pane back to the results tells the ViewModel.
    val destination = navigator.currentDestination?.pane
    LaunchedEffect(destination) {
        if (destination == ListDetailPaneScaffoldRole.List && currentReviewOpen && !currentTwoPane) actions.backToResults()
    }

    MatchPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        listPane = {
            AnimatedPane(modifier = Modifier.preferredWidth(LIST_PANE_WIDTH).testTag(FIND_PANE_TAG)) {
                FindPane(
                    state = findState,
                    bookId = bookId,
                    highlightPicked = isTwoPane,
                    actions = actions,
                    onBack = onBack,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                ReviewPane(
                    state = reviewState,
                    bookId = bookId,
                    bookTitle = findState.yourCopy?.title,
                    viewerId = viewerId,
                    isTwoPane = isTwoPane,
                    actions = actions,
                )
            }
        },
    )
}

/** The results pane's width beside Review: room for a cover, a title and three reasons. */
private val LIST_PANE_WIDTH = androidx.compose.ui.unit.Dp(420f)
