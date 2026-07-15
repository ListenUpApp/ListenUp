package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TagError
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
 * Seven row-level target `NotFound` failures are folded: six domain-specific `*.NotFound` subtypes,
 * plus the generic [SyncError.NotFound] — [com.calypsan.listenup.api.EntityService.deleteEntity] has
 * no dedicated `EntityError` hierarchy and fails with the base sync substrate's `NotFound` instead.
 * Never a sub-entity miss like [TagError.BookNotFound], [CollectionError.BookNotFound], or
 * [ContributorError.AliasNotFound], which are genuine failures that must surface.
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
        this is SyncError.NotFound
