@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId

/**
 * Client-side write surface for contributor editing.
 *
 * [updateContributor] is offline-first; [deleteContributor], [mergeContributor],
 * and [unmergeContributor] are RPC-backed and server-canonical. The firehose delivers
 * authoritative state back via
 * [com.calypsan.listenup.client.data.sync.domains.contributorsDomain].
 */
interface ContributorEditRepository {
    /**
     * Applies the PATCH payload [patch] to the contributor identified by [id].
     *
     * Every non-null field on [patch] replaces the current value; null fields
     * leave existing state untouched. Writes Room immediately and enqueues a
     * durable pending op, so an edit made offline persists and replays on
     * reconnect. The server's authoritative state still arrives via the firehose and
     * reconciles Room.
     */
    suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit>

    /**
     * Hard-deletes all `book_contributors` junction rows referencing [id],
     * then soft-deletes the contributor row. The server emits sync events for
     * the affected books and the contributor; clients update Room reactively.
     */
    suspend fun deleteContributor(id: ContributorId): AppResult<Unit>

    /**
     * Frames the contributor's current photo. Offline-first: written to Room and queued like any edit, and bound to the
     * version of the photo this device shows. Fails with a `ValidationError` naming `imageFrame` when there is no photo.
     */
    suspend fun setImageFrame(
        id: ContributorId,
        frame: ImageFrame,
    ): AppResult<Unit>

    /**
     * Records on this device how a newly picked photo ([imageData]) is framed — or, when [frame] is null, that it has no
     * frame — before it is uploaded, so the frame shows at once and the upload can carry it. Never queued: the frame
     * travels in the same request as its bytes.
     */
    suspend fun recordNewPhotoFrame(
        id: ContributorId,
        imageData: ByteArray,
        frame: ImageFrame?,
    ): AppResult<Unit>

    /**
     * Merges [source] into [target]. After the firehose round trip:
     * - The target contributor's `aliases` Room rows gain source's name.
     * - All books that referenced source point at target (with credited_as preserved).
     * - Source is soft-deleted.
     *
     * Server-canonical operation — no optimistic Room writes; the firehose delivers the
     * authoritative state.
     *
     * Returns [com.calypsan.listenup.api.error.ContributorError.MergeSelfTarget] when
     * `source == target`. Returns [com.calypsan.listenup.api.error.ContributorError.NotFound]
     * when either is missing or already tombstoned.
     */
    suspend fun mergeContributor(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit>

    /**
     * Splits [aliasName] back into its own fresh contributor. Books credited as
     * [aliasName] re-link to the new contributor; books credited otherwise stay
     * with the target. Returns the new contributor's id on Success so callers
     * can navigate to it.
     *
     * Returns [com.calypsan.listenup.api.error.ContributorError.AliasNotFound] when
     * the alias isn't on the target.
     */
    suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId>
}
