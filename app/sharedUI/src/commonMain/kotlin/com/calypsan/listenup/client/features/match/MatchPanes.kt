package com.calypsan.listenup.client.features.match

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.TwoPaneMinWidth

/**
 * The Match details layout, for a book or a person: one pane on compact and medium widths (Review pushes over
 * the results, with predictive back), results | review side by side from [TwoPaneMinWidth]. The mode is
 * reported through [onTwoPaneChanged] whenever it changes, so two panes open the best Strong match at once.
 * The session leads: [reviewOpen] opens and closes Review, and a system back that took one pane back to the
 * results calls [onBackToResults].
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun MatchPanes(
    reviewOpen: Boolean,
    onTwoPaneChanged: (Boolean) -> Unit,
    onBackToResults: () -> Unit,
    listPane: @Composable (isTwoPane: Boolean) -> Unit,
    detailPane: @Composable (isTwoPane: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    // The window's real width, not its size-class bucket: the default buckets stop at 840dp, so a
    // 960dp breakpoint read through them would never be reached.
    val isTwoPane = LocalWindowInfo.current.containerDpSize.width >= TwoPaneMinWidth
    val directive =
        remember(adaptiveInfo, isTwoPane) {
            calculatePaneScaffoldDirective(adaptiveInfo).copy(maxHorizontalPartitions = if (isTwoPane) 2 else 1)
        }
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(scaffoldDirective = directive)
    val currentReviewOpen by rememberUpdatedState(reviewOpen)
    val currentTwoPane by rememberUpdatedState(isTwoPane)
    val currentOnTwoPaneChanged by rememberUpdatedState(onTwoPaneChanged)
    val currentOnBackToResults by rememberUpdatedState(onBackToResults)

    LaunchedEffect(isTwoPane) { currentOnTwoPaneChanged(isTwoPane) }

    // The session leads: a pick opens Review, Back to results closes it.
    LaunchedEffect(reviewOpen) {
        val onReview = navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail
        if (reviewOpen && !onReview) navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        if (!reviewOpen && onReview && navigator.canNavigateBack()) navigator.navigateBack()
    }
    // A system back (predictive on Android) that took one pane back to the results tells the session.
    val destination = navigator.currentDestination?.pane
    LaunchedEffect(destination) {
        if (destination == ListDetailPaneScaffoldRole.List && currentReviewOpen && !currentTwoPane) {
            currentOnBackToResults()
        }
    }

    MatchPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        listPane = {
            AnimatedPane(modifier = Modifier.preferredWidth(LIST_PANE_WIDTH).testTag(FIND_PANE_TAG)) {
                listPane(isTwoPane)
            }
        },
        detailPane = { AnimatedPane { detailPane(isTwoPane) } },
    )
}

/** The results pane's width beside Review: room for a cover or photo, a name and three facts. */
private val LIST_PANE_WIDTH = 420.dp
