package com.calypsan.listenup.server.services

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context marker that suppresses the per-event `user_stats` upsert and
 * [PublicProfileMaintainer.refresh] inside [StatsRecorder.record] while a bulk write runs, WITHOUT
 * suppressing the durable source-row write or activity emission. Mirrors
 * [com.calypsan.listenup.server.sync.FirehoseSuppressed]'s shape and rationale.
 *
 * Present in the context → [StatsEvent.BookCompleted] and [StatsEvent.ListeningSessionClosed] still
 * write their source row (`book_reads` / the caller-committed `listening_events` row) and still emit
 * their primary activity row (`FINISHED_BOOK` / `LISTENING_SESSION`, both historically dated via
 * `occurredAt` so they don't spam the live feed), but skip the `user_stats` upsert, the
 * `public_profiles` refresh, and (for listening sessions) streak/listening-milestone activity —
 * those would read stale base totals mid-import and either never fire or misfire repeatedly.
 * [com.calypsan.listenup.server.absimport.ImportApplier] writes every source row through this
 * marker, then ends with one [StatsEvent.BulkRecompute] per affected user — one refresh per user
 * per import, never one per row.
 *
 * Defaults to *not deferred*: absence of the marker is the universal case (every live write path),
 * so existing call sites are unaffected.
 */
object StatsCascadeDeferred : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<StatsCascadeDeferred>
}
