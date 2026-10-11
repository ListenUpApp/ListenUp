package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.worldevent.EventsBatch
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult

/**
 * Folds a row-level "not found" failure on a delete op to [AppResult.Success] — the delete-tombstone
 * idempotency rule.
 *
 * A delete op re-fired after a provably-sent-but-lost response
 * ([com.calypsan.listenup.api.error.TransportError.OutcomeUnknown]) hits an already-tombstoned row, so
 * the server returns its domain's `NotFound`. Without this fold that surfaces as a spurious
 * dead-letter, yet NotFound on a delete means the desired end state (the row is gone) is *already*
 * true — i.e. success. Applied at every delete-tombstone sender binding so a lost-then-retried delete
 * drains cleanly instead of quarantining.
 *
 * Only the row-level target `*.NotFound` failures are folded — never a sub-entity miss like
 * [TagError.BookNotFound], [CollectionError.BookNotFound], or [ContributorError.AliasNotFound], which
 * are genuine failures that must surface.
 *
 * The `entities` sender also folds its Upsert through here: the server answers an upsert on a tombstoned
 * entity with [EntityError.NotFound], and the delete has already won — the tombstone reaches the mirror
 * through sync, so the queued edit drains instead of dead-lettering against an entity that is gone.
 */
internal fun AppResult<Unit>.orSuccessIfNotFound(): AppResult<Unit> =
    if (this is AppResult.Failure && error.isDeleteTargetNotFound()) AppResult.Success(Unit) else this

private fun com.calypsan.listenup.api.error.AppError.isDeleteTargetNotFound(): Boolean =
    this is TagError.NotFound ||
        this is ShelfError.NotFound ||
        this is CollectionError.NotFound ||
        this is GenreError.NotFound ||
        this is SeriesError.NotFound ||
        this is ContributorError.NotFound ||
        this is EntityError.NotFound ||
        this is ReadingOrderError.NotFound ||
        this is WorldEventError.NotFound

/**
 * The `world_events` sender's fold. A batch of one is a single edit or delete, so its NotFound means a delete
 * has already won ([orSuccessIfNotFound]). A batch of several stands or falls together, so its NotFound
 * surfaces — folding it would silently drop the batch's other ops.
 */
internal fun AppResult<Unit>.orSuccessIfSingleOpNotFound(batch: EventsBatch): AppResult<Unit> =
    if (batch.ops.size == 1) orSuccessIfNotFound() else this
