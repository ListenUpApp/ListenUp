package com.calypsan.listenup.client.data.sync.domains

/**
 * The per-domain Room-write seam: HOW payloads become local rows. Policy — WHETHER
 * and WHEN to write — lives in [ConflictPolicy]/[DeleteSemantics] and is enforced by
 * [ComposedSyncDomainHandler]; implementations of this interface contain mapping and
 * genuinely domain-specific side effects only (e.g. books' cover-hash invalidation).
 * Two declared exceptions live here by design: [ConflictPolicy.AppendOnly]'s
 * insert-if-absent guard is the apply's DAO conflict strategy, and the soft-vs-hard
 * distinction behind [DeleteSemantics] is how the apply implements [tombstoneById].
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

    /**
     * Apply a catch-up tombstone from the full payload.
     *
     * The `deletedAt ?: <fallback>` expression is deliberately per-domain: most payloads
     * fall back to `updatedAt`, the junction payloads only carry `createdAt`. A generic
     * default can't see payload fields without new contract-side interfaces, so the
     * invariant is pinned by tests instead — every Full-digest domain asserts a
     * tombstoned row survives in `digestRows`.
     */
    suspend fun tombstoneFromItem(item: T)
}
