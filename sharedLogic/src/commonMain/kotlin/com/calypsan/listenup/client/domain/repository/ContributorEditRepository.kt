package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.ContributorId

/**
 * Client-side write surface for contributor editing.
 *
 * RPC-backed. SSE delivers authoritative state back via
 * [com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler].
 *
 * Merge / unmerge are deliberately absent in Books-C1 — server-canonical
 * versions land in Books-C2 alongside the `contributor_aliases` substrate.
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
}
