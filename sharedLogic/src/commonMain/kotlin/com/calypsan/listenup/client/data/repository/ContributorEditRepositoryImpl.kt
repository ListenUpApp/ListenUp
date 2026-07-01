package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.remote.ContributorRpcFactory
import com.calypsan.listenup.client.data.sync.ContributorEdit
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

/**
 * Never-stranded cap on a contributor merge. The server does O(books) work for a merge and has been
 * observed to hang indefinitely under real-transport conditions (the RPC response never arrives,
 * leaving the edit screen spinning forever). Bounding the wait turns that into a retryable error.
 */
private const val MERGE_TIMEOUT_MS = 30_000L

/**
 * Contributor editor: offline-first update, server-canonical merge/delete.
 *
 * [updateContributor] writes the patch into Room immediately and enqueues a
 * durable pending op (the same outbox the playback-position writes use), so
 * an edit made offline persists and replays on reconnect rather than failing
 * with a [com.calypsan.listenup.api.error.ServerConnectError]. The
 * authoritative state still arrives via the SSE sync engine and reconciles
 * through [com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler].
 *
 * [deleteContributor], [mergeContributor], and [unmergeContributor] stay pure
 * RPC dispatchers — no optimistic Room writes; the SSE echo from the server is
 * their single write path back into Room. Wire [WireAppResult] values returned
 * by the RPC service are converted to the client-layer [AppResult] at this
 * boundary, following the same pattern as [BookEditRepositoryImpl].
 */
internal class ContributorEditRepositoryImpl(
    private val contributorRpcFactory: ContributorRpcFactory,
    private val contributorDao: ContributorDao,
    private val offlineEditor: OfflineEditor,
) : ContributorEditRepository {
    override suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit> =
        offlineEditor.edit(ContributorEdit, id.value, patch) {
            contributorDao.getById(id.value)?.let { existing ->
                contributorDao.upsert(
                    existing.copy(
                        name = patch.name ?: existing.name,
                        sortName = patch.sortName ?: existing.sortName,
                        asin = patch.asin ?: existing.asin,
                        description = patch.description ?: existing.description,
                        imagePath = patch.imagePath ?: existing.imagePath,
                        birthDate = patch.birthDate ?: existing.birthDate,
                        deathDate = patch.deathDate ?: existing.deathDate,
                        website = patch.website ?: existing.website,
                        // revision + updatedAt deliberately untouched.
                    ),
                )
            }
        }

    override suspend fun deleteContributor(id: ContributorId): AppResult<Unit> =
        rpcCallUnit { contributorRpcFactory.contributorService().deleteContributor(id) }

    override suspend fun mergeContributor(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit> =
        // withTimeoutOrNull (not withTimeout) so a stalled merge becomes an in-band Timeout failure
        // the screen can show + offer retry, rather than a CancellationException that tears down the
        // caller. A genuine parent cancellation still propagates through normally.
        withTimeoutOrNull(MERGE_TIMEOUT_MS) {
            rpcCallUnit { contributorRpcFactory.contributorService().mergeContributors(source, target) }
        } ?: AppResult.Failure(TransportError.Timeout(debugInfo = "contributor merge exceeded ${MERGE_TIMEOUT_MS}ms"))

    override suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId> =
        rpcCall { contributorRpcFactory.contributorService().unmergeContributor(contributorId, aliasName) }

    /**
     * Run an RPC call that returns [T], converting [WireAppResult] → [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure]
     * via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Contributor edit RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }

    /**
     * Run an RPC call that returns [Unit], converting [WireAppResult] → [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure]
     * via [ErrorMapper].
     */
    private suspend fun rpcCallUnit(block: suspend () -> WireAppResult<Unit>): AppResult<Unit> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(Unit)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Contributor edit RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}
