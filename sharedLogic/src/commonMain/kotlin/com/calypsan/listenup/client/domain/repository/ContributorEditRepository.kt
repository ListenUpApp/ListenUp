package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.ContributorId

/**
 * Client-side write surface for contributor editing.
 *
 * RPC-backed. SSE delivers authoritative state back via
 * [com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler].
 */
interface ContributorEditRepository {
    /**
     * Applies the PATCH payload [patch] to the contributor identified by [id].
     *
     * Every non-null field on [patch] replaces the current value; null fields
     * leave existing state untouched. The server emits an SSE event with the
     * updated payload on success; clients update Room reactively.
     */
    suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit>

    /**
     * Hard-deletes all `book_contributors` junction rows referencing [id],
     * then soft-deletes the contributor row. The server emits SSE events for
     * the affected books and the contributor; clients update Room reactively.
     */
    suspend fun deleteContributor(id: ContributorId): AppResult<Unit>

    /**
     * Merges [source] into [target]. After the SSE round trip:
     * - The target contributor's `aliases` Room rows gain source's name.
     * - All books that referenced source point at target (with credited_as preserved).
     * - Source is soft-deleted.
     *
     * Server-canonical operation — no optimistic Room writes; SSE delivers the
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
