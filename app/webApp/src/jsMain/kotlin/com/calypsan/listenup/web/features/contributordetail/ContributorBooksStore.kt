package com.calypsan.listenup.web.features.contributordetail

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Contributor Books state stream, plus the teardown for it.
 *
 * Same shape as [ContributorDetailSession],
 * for the same reason: a browser has no `ViewModelStore` to hand a ViewModel's lifetime to, so the
 * page owns it — a session opens when the browser starts showing one person in one role, and
 * closes when it stops.
 */
class ContributorBooksSession(
    val state: StateFlow<ContributorBooksUiState>,
    val close: () -> Unit,
)

/**
 * How the page gets its state. Production resolves the real ViewModel out of the client graph
 * ([graphContributorBooks]); specs hand over a fixed state instead ([fixedContributorBooks]).
 */
typealias OpenContributorBooks = (contributorId: String, role: String) -> ContributorBooksSession

/**
 * The production source: the shared [ContributorBooksViewModel], resolved from the started Koin
 * graph and pointed at one (contributor, role) pair.
 *
 * The store key carries the role as well as the id. Both halves select the upstream, so a reader
 * moving from a person's Author list to their Narrator list is a different scope, not the same one
 * re-pointed — and keying on the id alone would hand the second list the first one's ViewModel.
 */
fun graphContributorBooks(koin: Koin): OpenContributorBooks =
    { contributorId, role ->
        val viewModel = koin.get<ContributorBooksViewModel>()
        val store = ViewModelStore().apply { put("$contributorId/$role", viewModel) }
        viewModel.loadBooks(contributorId, role)
        ContributorBooksSession(state = viewModel.state, close = store::clear)
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedContributorBooks(state: ContributorBooksUiState): OpenContributorBooks =
    { _, _ -> ContributorBooksSession(state = MutableStateFlow(state), close = {}) }
