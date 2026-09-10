package com.calypsan.listenup.web.features.contributoredit

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.contributoredit.ContributorCandidate
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditNavAction
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiState
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Contributor Edit session: the state, the merge candidates, the navigation the ViewModel
 * asks for, and the one channel every change goes back through.
 *
 * Small for a screen this size, because the ViewModel takes a sealed [ContributorEditUiEvent] —
 * the same shape Book Edit uses. The merge query is the exception: it is a plain setter on the
 * ViewModel rather than an event, so it rides alongside.
 */
class ContributorEditSession(
    val state: StateFlow<ContributorEditUiState>,
    /** Recomputed only while the merge picker is open; empty otherwise. */
    val mergeCandidates: StateFlow<List<ContributorCandidate>>,
    val navActions: Flow<ContributorEditNavAction>,
    val onEvent: (ContributorEditUiEvent) -> Unit,
    val onMergeQuery: (String) -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenContributorEdit = (contributorId: String) -> ContributorEditSession

/**
 * The production source: the shared [ContributorEditViewModel], pointed at [contributorId].
 *
 * `loadContributor` fires here, as Book Edit's session does: the load is what the session IS, and
 * leaving it to the page means every future caller has to remember the same two-step.
 */
fun graphContributorEdit(koin: Koin): OpenContributorEdit =
    { contributorId ->
        val viewModel = koin.get<ContributorEditViewModel>()
        val store = ViewModelStore().apply { put(contributorId, viewModel) }
        viewModel.loadContributor(contributorId)
        ContributorEditSession(
            state = viewModel.state,
            mergeCandidates = viewModel.mergeCandidates,
            navActions = viewModel.navActions,
            onEvent = viewModel::onEvent,
            onMergeQuery = viewModel::onMergeQueryChange,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedContributorEdit(
    state: ContributorEditUiState,
    mergeCandidates: List<ContributorCandidate> = emptyList(),
    navActions: Flow<ContributorEditNavAction> = emptyFlow(),
    onEvent: (ContributorEditUiEvent) -> Unit = {},
    onMergeQuery: (String) -> Unit = {},
): OpenContributorEdit =
    {
        ContributorEditSession(
            state = MutableStateFlow(state),
            mergeCandidates = MutableStateFlow(mergeCandidates),
            navActions = navActions,
            onEvent = onEvent,
            onMergeQuery = onMergeQuery,
            close = {},
        )
    }
