package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.world.EventsBatch
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for the Story World unified event log.
 *
 * Events are library-shared world data, dual-homed under a series or a standalone book —
 * unlike [ReadingOrderService]'s user-owned reading orders, there is no per-caller ownership:
 * any authenticated caller may read via [listForBook]/[listForEntity]/[listForWorld], and any
 * caller holding the metadata-edit permission
 * ([com.calypsan.listenup.api.error.AuthError.PermissionDenied] otherwise) may write via
 * [applyBatch] — the same curation model [EntityService] uses for entities.
 *
 * [applyBatch] is the single write entry point: every event lifecycle edit — a brand-new event, an
 * edit to an existing one, a soft-delete — rides one [com.calypsan.listenup.api.dto.world.WorldEventOp]
 * inside an [EventsBatch], applied atomically. Both op variants are last-write-wins / idempotent
 * (see [EventsBatch]'s KDoc), so replaying a queued offline batch is always safe to retry.
 */
@Rpc
interface WorldEventService {
    /**
     * Applies every op in [batch] atomically: either every op takes effect, or (on a typed
     * failure) none does.
     *
     * Gated on the metadata-edit permission
     * ([com.calypsan.listenup.api.error.AuthError.PermissionDenied] when denied), checked once for
     * the whole batch. Each [com.calypsan.listenup.api.dto.world.WorldEventOp.Upsert] is validated
     * for shape (exactly one of `homeSeriesId`/`homeBookId`, the anchor pairing, and the
     * per-[com.calypsan.listenup.api.sync.WorldEventType] rules) before anything is applied; a
     * [com.calypsan.listenup.api.dto.world.WorldEventOp.Delete] targeting a nonexistent event fails
     * with [com.calypsan.listenup.api.error.SyncError.NotFound]. Either failure aborts the whole
     * batch — nothing in [batch] is applied.
     *
     * @param batch The ordered operations to apply atomically.
     */
    suspend fun applyBatch(batch: EventsBatch): AppResult<Unit>

    /**
     * Returns every live (non-tombstoned) event anchored to [bookId].
     *
     * Open to any authenticated caller — events are library-shared, not access-gated per-book the
     * way [BookService] content is.
     *
     * @param bookId Identifies the book whose anchored events are requested.
     */
    suspend fun listForBook(bookId: String): AppResult<List<WorldEventSyncPayload>>

    /**
     * Returns every live (non-tombstoned) event that mentions the entity identified by
     * [entityId] — as its subject, its object, or an inline `@entity` token in its text.
     *
     * Open to any authenticated caller.
     *
     * @param entityId Identifies the entity whose mentioning events are requested.
     */
    suspend fun listForEntity(entityId: String): AppResult<List<WorldEventSyncPayload>>

    /**
     * Returns every live (non-tombstoned) event namespaced under exactly one of [homeSeriesId] /
     * [homeBookId].
     *
     * Open to any authenticated caller. Fails with
     * [com.calypsan.listenup.api.error.ValidationError] unless exactly one of [homeSeriesId] /
     * [homeBookId] is non-null — the same dual-home rule
     * [com.calypsan.listenup.api.sync.WorldEventSyncPayload] writes enforce.
     *
     * @param homeSeriesId The series whose events are requested, or null when querying by book.
     * @param homeBookId The standalone book whose events are requested, or null when querying by series.
     */
    suspend fun listForWorld(
        homeSeriesId: String?,
        homeBookId: String?,
    ): AppResult<List<WorldEventSyncPayload>>
}
