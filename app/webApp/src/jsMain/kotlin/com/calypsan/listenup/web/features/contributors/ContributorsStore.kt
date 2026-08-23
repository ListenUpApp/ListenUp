package com.calypsan.listenup.web.features.contributors

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.core.appCoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import org.koin.core.Koin

/**
 * An open Contributors list session: the contributors for the currently selected role, and the
 * teardown for it.
 *
 * [state] starts `null` — the query hasn't answered yet — and only becomes a list once
 * [ContributorRepository.observeContributorsByRole] actually emits. Seeding it with `emptyList()`
 * would tell the page a role has zero contributors before the database had a chance to say
 * otherwise, which is a wrong answer, not a placeholder one.
 *
 * There is no shared ViewModel for this list — it rides
 * [ContributorRepository.observeContributorsByRole] directly, the same repository-backed shape
 * `PlaybackSession` uses over `PlaybackManager` rather than a ViewModel. A browser has no
 * `ViewModelStore` regardless of what sits behind a session, so the page still owns this one's
 * lifetime and closes it on the way out — the same arrangement
 * [com.calypsan.listenup.web.features.library.LibrarySession] makes.
 */
class ContributorsSession(
    val state: StateFlow<List<ContributorWithBookCount>?>,
    val close: () -> Unit,
)

/**
 * How the page gets its list. Production resolves the repository out of the client graph and
 * observes [role] ([graphContributors]); specs hand over a fixed list instead
 * ([fixedContributors]), so the letter-grouping and role-toggle contracts can be driven without a
 * database behind them.
 */
typealias OpenContributors = (role: ContributorRole) -> ContributorsSession

/**
 * The production source: [ContributorRepository.observeContributorsByRole], resolved from the
 * started Koin graph and turned hot for the page's lifetime.
 *
 * A failing observe is logged, then falls back to an empty list rather than leaving the page stuck
 * on whatever it last rendered — the same recovery `LibraryViewModel` applies to this identical
 * repository call, logging before it emits rather than swallowing the failure outright.
 */
fun graphContributors(koin: Koin): OpenContributors =
    { role ->
        val repository = koin.get<ContributorRepository>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + appCoroutineExceptionHandler)
        // Typed nullable up front: `Flow` is declared `out T`, so this widening is just a subtype
        // assignment, but `stateIn` below needs it in hand before `null` is a legal initial value.
        val contributors: Flow<List<ContributorWithBookCount>?> =
            repository
                .observeContributorsByRole(role.apiValue)
                .catch { e ->
                    if (e is CancellationException) throw e
                    console.error("observeContributorsByRole($role) failed; emitting empty list: ${e.message}")
                    emit(emptyList())
                }
        ContributorsSession(
            state = contributors.stateIn(scope, SharingStarted.Eagerly, null),
            close = scope::cancel,
        )
    }

/** A session over a list that never changes — the shape specs use in place of the graph. */
fun fixedContributors(contributors: List<ContributorWithBookCount>?): OpenContributors =
    { ContributorsSession(state = MutableStateFlow(contributors), close = {}) }
