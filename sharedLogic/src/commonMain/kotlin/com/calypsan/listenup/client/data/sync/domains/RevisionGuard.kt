package com.calypsan.listenup.client.data.sync.domains

/**
 * Staleness guard for inbound sync applies on revisioned domains: an inbound
 * snapshot or tombstone whose server revision is STRICTLY below the local
 * row's stored revision is stale (a catch-up page or replayed frame that lost
 * a race against a fresher write) and must be skipped.
 *
 * The comparison is deliberately strict (`local > incoming` skips) — an
 * EQUAL-revision apply must go through, because digest repair
 * (`forceFullResync` / [com.calypsan.listenup.client.data.sync.SyncReconciler])
 * legitimately rewrites rows at unchanged revisions to fix content-only drift.
 * Skipping equals would make that drift permanent.
 *
 * [localRevision] receives the sync id (the SSE envelope id — composite-key
 * domains parse their `"a:b"` synthetic form exactly as their
 * [MirrorApply.tombstoneById] does) and returns the local row's revision
 * INCLUDING tombstoned rows, or null when the row has never been seen (first
 * sight always applies). It runs inside the apply's write transaction.
 */
internal class RevisionGuard<T : Any>(
    val incomingRevision: (T) -> Long,
    val localRevision: suspend (syncId: String) -> Long?,
)
