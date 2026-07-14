package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorMutation
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Never-stranded cap on a contributor merge. The server does O(books) work for a merge and has been
 * observed to hang indefinitely under real-transport conditions (the RPC response never arrives,
 * leaving the edit screen spinning forever). Bounding the wait turns that into an honest failure —
 * the bound is enforced by the [RpcChannel], whose [RpcChannel.call] runs the block under
 * `withTimeout`. Because the merge frame was already SENT when the bound trips, an expiry folds to a
 * NON-retryable [com.calypsan.listenup.api.error.TransportError.OutcomeUnknown], not a retryable
 * Timeout: the merge may have committed, so it must not be blindly re-fired.
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
 * [deleteContributor] is offline-first too: it soft-deletes the contributor row and cascade-removes
 * its `book_contributors` credits (mirroring the server's `deleteContributor` cascade), then
 * enqueues a durable [ContributorMutation.Delete] op on the same `contributors` channel.
 * [mergeContributor] and [unmergeContributor] stay pure RPC dispatchers — they relink junctions and
 * mint identities server-side, so they can't be mirrored optimistically. Both route through the
 * [channel], which bounds the call, self-heals the transport, and folds any fault to a typed
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
        offlineEditor.edit(OutboxChannels.Contributors, id.value, ContributorMutation.Update(patch)) {
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

    /**
     * Offline-first: soft-delete the contributor (preserving its revision so the echo re-applies the
     * authoritative tombstone on drain) and cascade-remove its `book_contributors` credits, then
     * enqueue a durable [ContributorMutation.Delete] op on the `contributors` channel keyed by the id.
     */
    override suspend fun deleteContributor(id: ContributorId): AppResult<Unit> {
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(
            OutboxChannels.Contributors,
            id.value,
            ContributorMutation.Delete,
            op = OpKind.Delete,
        ) {
            contributorDao
                .getById(id.value)
                ?.let { contributorDao.softDelete(id = id, deletedAt = now, revision = it.revision) }
            contributorDao.deleteAllBookContributorsForContributor(id.value)
        }
    }

    override suspend fun mergeContributor(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit> =
        // The channel bounds the call at MERGE_TIMEOUT: a stalled merge folds to a NON-retryable
        // TransportError.OutcomeUnknown the screen can show honestly, rather than a
        // CancellationException that tears down the caller. The merge frame was sent, so it may have
        // committed — the UI must NOT blindly retry (that would double-apply). A genuine parent
        // cancellation still propagates through normally.
        channel.call(timeout = MERGE_TIMEOUT) { it.mergeContributors(source, target) }

    override suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId> = channel.call { it.unmergeContributor(contributorId, aliasName) }
}
