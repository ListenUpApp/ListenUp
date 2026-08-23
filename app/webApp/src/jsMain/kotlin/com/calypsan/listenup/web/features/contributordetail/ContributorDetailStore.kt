package com.calypsan.listenup.web.features.contributordetail

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Contributor Detail state stream, plus the teardown for it.
 *
 * Mirrors [com.calypsan.listenup.web.features.bookdetail.BookDetailSession]: a browser has no
 * `ViewModelStore` to hand a ViewModel's lifetime to, so the page owns it — a session opens when
 * the browser starts showing a contributor and closes when it stops.
 */
class ContributorDetailSession(
    val state: StateFlow<ContributorDetailUiState>,
    val close: () -> Unit,
)

/**
 * How the page gets its state. Production resolves the real ViewModel out of the client graph
 * ([graphContributorDetail]); specs hand over a fixed state instead ([fixedContributorDetail]), so
 * the hero, panel and series contracts can be driven without a database behind them.
 */
typealias OpenContributorDetail = (contributorId: String) -> ContributorDetailSession

/**
 * The production source: the shared [ContributorDetailViewModel], resolved from the started Koin
 * graph and pointed at [contributorId].
 *
 * The ViewModel goes into a [ViewModelStore] of its own, for the same reason
 * [com.calypsan.listenup.web.features.bookdetail.graphBookDetail] does: clearing the store is the
 * only sanctioned way to end a ViewModel's `viewModelScope`, and here that scope is one visited
 * contributor.
 */
fun graphContributorDetail(koin: Koin): OpenContributorDetail =
    { contributorId ->
        val viewModel = koin.get<ContributorDetailViewModel>()
        val store = ViewModelStore().apply { put(contributorId, viewModel) }
        viewModel.loadContributor(contributorId)
        ContributorDetailSession(state = viewModel.state, close = store::clear)
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedContributorDetail(state: ContributorDetailUiState): OpenContributorDetail =
    { ContributorDetailSession(state = MutableStateFlow(state), close = {}) }
