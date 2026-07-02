package com.calypsan.listenup.client.data.sync.domains

/**
 * The per-domain Room-write seam: HOW payloads become local rows. Policy — WHETHER
 * and WHEN to write — lives in [ConflictPolicy]/[DeleteSemantics] and is enforced by
 * [ComposedSyncDomainHandler]; implementations of this interface contain mapping and
 * genuinely domain-specific side effects only (e.g. books' cover-hash invalidation).
 *
 * Every method runs inside the composed handler's IMMEDIATE write transaction.
 */
internal interface MirrorApply<T : Any> {
    /** Map [payload] to Room rows and upsert. */
    suspend fun upsert(payload: T)

    /**
     * Apply an id-only tombstone (SSE `Deleted`). Domains declaring
     * [DeleteSemantics.CatchUpOnly] are never called here and implement this as
     * `error(...)` — honest unreachability over a silent no-op.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Apply a catch-up tombstone from the full payload. */
    suspend fun tombstoneFromItem(item: T)
}
