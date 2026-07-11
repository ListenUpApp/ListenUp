package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import kotlin.time.Duration.Companion.seconds

/**
 * Never-stranded cap on a contributor merge. The server does O(books) work for a merge and has been
 * observed to hang indefinitely under real-transport conditions (the RPC response never arrives,
 * leaving the edit screen spinning forever). Bounding the wait turns that into a retryable error —
 * the bound is now enforced by the [RpcChannel], whose [RpcChannel.call] runs the block under
 * `withTimeout` and folds an expiry to a retryable [com.calypsan.listenup.api.error.TransportError.Timeout].
 */
private val MERGE_TIMEOUT = 30.seconds

/**
 * Contributor editor: offline-first update, server-canonical merge/delete.
 *
 * [updateContributor] writes the patch into Room immediately and enqueues a
 * durable pending op (the same outbox the playback-position writes use), so
 * an edit made offline persists and replays on reconnect rather than failing
 * with a [com.calypsan.listenup.api.error.ServerConnectError]. The
 * authoritative state still arrives via the SSE sync engine and reconciles
 * through [com.calypsan.listenup.client.data.sync.domains.contributorsDomain].
 *
 * [deleteContributor], [mergeContributor], and [unmergeContributor] stay pure
 * RPC dispatchers — no optimistic Room writes; the SSE echo from the server is
 * their single write path back into Room. All three route through the [channel],
 * which bounds the call, self-heals the transport, and folds any fault to a typed
 * [AppResult.Failure], following the same pattern as [BookEditRepositoryImpl].
 */
internal class ContributorEditRepositoryImpl(
    private val channel: RpcChannel<ContributorService>,
    private val contributorDao: ContributorDao,
    private val offlineEditor: OfflineEditor,
) : ContributorEditRepository {
    override suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit> =
        offlineEditor.edit(OutboxChannels.Contributors, id.value, patch) {
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
        channel.call { it.deleteContributor(id) }

    override suspend fun mergeContributor(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit> =
        // The channel bounds the call at MERGE_TIMEOUT: a stalled merge folds to a retryable
        // TransportError.Timeout the screen can show + offer retry, rather than a
        // CancellationException that tears down the caller. A genuine parent cancellation still
        // propagates through normally.
        channel.call(timeout = MERGE_TIMEOUT) { it.mergeContributors(source, target) }

    override suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId> = channel.call { it.unmergeContributor(contributorId, aliasName) }
}
