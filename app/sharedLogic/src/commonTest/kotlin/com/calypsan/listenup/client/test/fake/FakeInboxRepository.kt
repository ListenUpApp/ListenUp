package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [InboxRepository]. [held] stands in for Room's held set, and a successful release removes
 * the books from it (a partial one, all but the books its `ReleaseIncomplete` names) — exactly what
 * the real write-through does — so specs exercise a consumer's real convergence path instead of a
 * local prune.
 *
 * [heldSource] swaps in a custom held-set flow (e.g. one that throws); [releaseGate], when set, holds
 * every [releaseBooks] call in flight until it completes, so a spec can act mid-release.
 */
class FakeInboxRepository : InboxRepository {
    val held = MutableStateFlow<Set<BookId>>(emptySet())
    var heldSource: Flow<Set<BookId>>? = null

    var releaseResult: AppResult<Unit> = AppResult.Success(Unit)
    var releaseGate: CompletableDeferred<Unit>? = null

    /** Every [releaseBooks] call, in order: the library id and the assignments it carried. */
    val releases = mutableListOf<Pair<String, Map<String, List<String>>>>()

    var scanIssues: AppResult<List<ScanIssue>> = AppResult.Success(emptyList())
    var scanIssueLoads = 0
        private set

    var dismissResult: AppResult<Unit> = AppResult.Success(Unit)

    /** Adds [ids] to the held set, after whatever is already held. */
    fun hold(vararg ids: String) {
        held.update { current -> current + ids.map { BookId(it) } }
    }

    override fun observeHeldBookIds(): Flow<Set<BookId>> = heldSource ?: held

    override suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit> {
        releases += libraryId to assignments
        releaseGate?.await()
        // Exactly the real write-through: every released book leaves the held set, and a partial
        // release's named books stay.
        val result = releaseResult
        val stayed =
            when (result) {
                is AppResult.Success -> emptySet()
                is AppResult.Failure -> (result.error as? CollectionError.ReleaseIncomplete)?.failedBookIds?.toSet()
            }
        if (stayed != null) {
            held.update { current -> current.filterNot { it.value in assignments.keys && it.value !in stayed }.toSet() }
        }
        return result
    }

    override suspend fun listScanIssues(): AppResult<List<ScanIssue>> {
        scanIssueLoads++
        return scanIssues
    }

    override suspend fun dismissScanIssue(issueId: String): AppResult<Unit> = dismissResult
}
