package com.calypsan.listenup.client.data.sync.domains

/**
 * The per-domain Room-write seam: HOW payloads become local rows. Policy — WHETHER
 * and WHEN to write — lives in [ConflictPolicy]/[DeleteSemantics] and is enforced by
 * [ComposedSyncDomainHandler]; implementations of this interface contain mapping and
 * genuinely domain-specific side effects only (e.g. books' cover-hash invalidation).
 * One declared exception lives here by design: [ConflictPolicy.AppendOnly]'s
 * insert-if-absent guard is the apply's DAO conflict strategy. The id-only SSE
 * `Deleted` tombstone is [DeleteSemantics.SoftDelete]'s hook, not a method here, so a
 * [DeleteSemantics.CatchUpOnly] domain can't be handed one.
 *
 * Every method runs inside the composed handler's IMMEDIATE write transaction.
 */
internal interface MirrorApply<T : Any> {
    /** Map [payload] to Room rows and upsert. */
    suspend fun upsert(payload: T)

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
