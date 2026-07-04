package com.calypsan.listenup.server.sync

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context marker that suppresses the per-write [ChangeBus] *publish*
 * while a bulk write runs, **without** suppressing the revision bump.
 *
 * Present in the context → [SyncableRepository.upsert] / [SyncableRepository.softDelete]
 * still bump the global revision counter and commit the row (so REST catch-up via
 * `pullSince` sees every row), but they skip `bus.publish(...)`. The lossy live-tail
 * ([ChangeBus]'s `replay = 256`, `DROP_OLDEST`) therefore never carries the burst —
 * the onboarding full-scan of an arbitrarily large library can no longer overflow the
 * buffer and trip a client `CursorStale` → catch-up spin.
 *
 * Scope: [com.calypsan.listenup.server.services.BookPersister] wraps a FULL-scan
 * persist in `withContext(FirehoseSuppressed) { ... }`. Incremental scans and every
 * non-scan write run without the marker, so they publish normally — they ARE live
 * deltas. The client reconciles the suppressed bulk write once, via REST catch-up,
 * after the `ScanEvent.Completed` that the persister emits at the end.
 *
 * Defaults to *publishing*: absence of the marker is the universal case, so all
 * existing write paths are unchanged.
 *
 * **Pairing rule — every `FirehoseSuppressed` bulk write path MUST broadcast exactly one
 * [SyncControl.LibraryDataChanged] after commit.** The suppressed rows land above the client
 * cursor with no live signal; the broadcast is the accelerator that makes connected clients
 * reconcile them now (via the client's lifecycle reconcile) instead of only after a restart.
 * A dropped frame still self-heals on the next lifecycle edge for any cursored domain, so the
 * broadcast is an accelerator, never the sole carrier. Current call sites:
 * [com.calypsan.listenup.server.services.BookPersister] (post-scan) and
 * [com.calypsan.listenup.server.absimport.ImportApplier] (post-import).
 */
object FirehoseSuppressed : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<FirehoseSuppressed>
}
