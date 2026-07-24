package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncPayload

/**
 * The sync-substrate view of an existing append-only row: just enough to decide whether an
 * inbound re-delivery must un-tombstone the row or converge its revision. Read via
 * [AppendOnlyMirrorApply.readMeta].
 */
internal data class AppendOnlyRowMeta(
    val deletedAt: Long?,
    val revision: Long,
)

/**
 * [MirrorApply] for [ConflictPolicy.AppendOnly] domains: rows are server-authored and written
 * exactly once, so [upsert] is insert-if-absent. Two substrate corrections still apply when an
 * existing id is re-delivered — and both are sync policy, not domain content, so they live here
 * once rather than being hand-written (or, worse, silently omitted) per domain:
 *
 *  - **Un-tombstone on live re-delivery.** A row the access-gate prune soft-deleted, later re-sent
 *    LIVE (a restored share re-delivers it via catch-up with `deletedAt = null`), must be restored.
 *    `deletedAt` is sync substrate, not append-only content. Without this the row stays tombstoned
 *    forever: the server digest and the client's tombstone-inclusive digest then agree on
 *    `(id, revision)`, so no reconcile can ever heal it.
 *  - **Revision converge.** Domain fields never change, but if the server re-upserts an id (an
 *    idempotent replay, or a future backfill bumps its revision), converge the local revision so
 *    the `(id, revision)` digest can never permanently drift on this client.
 *
 * Subclasses implement only the four Room primitives; the probe → restore → converge skeleton is
 * domain-agnostic and identical everywhere. [MirrorApply.tombstoneFromItem] stays per-domain (the
 * `deletedAt ?: <fallback>` expression reads payload fields the base can't see).
 */
internal abstract class AppendOnlyMirrorApply<T : SyncPayload> : MirrorApply<T> {
    /** Insert the row for a never-before-seen [payload] — the append. */
    protected abstract suspend fun insert(payload: T)

    /** The stored row's substrate fields, or null when [id] has never been seen. */
    protected abstract suspend fun readMeta(id: String): AppendOnlyRowMeta?

    /** Clear the tombstone on [id] and align its [revision] (live re-delivery). */
    protected abstract suspend fun restore(
        id: String,
        revision: Long,
    )

    /** Advance only the [revision] of the existing row [id] (idempotent re-upsert). */
    protected abstract suspend fun updateRevision(
        id: String,
        revision: Long,
    )

    final override suspend fun upsert(payload: T) {
        val existing = readMeta(payload.id)
        when {
            existing == null -> insert(payload)
            existing.deletedAt != null && payload.deletedAt == null -> restore(payload.id, payload.revision)
            existing.revision != payload.revision -> updateRevision(payload.id, payload.revision)
        }
    }
}
