package com.calypsan.listenup.client.data.sync.domains

/**
 * What a mirrored domain does with a firehose `SyncEvent.Deleted` frame. Catch-up
 * tombstones always route to [MirrorApply.tombstoneFromItem] regardless of this
 * setting (the catch-up item carries the full payload; the firehose frame carries only
 * the server id).
 */
internal sealed interface DeleteSemantics {
    /**
     * Soft-delete the row by id: set `deletedAt`, keep the row and its `revision`.
     * Used by entity domains AND junction-row domains (`book_tags`, `collection_books`,
     * …) — junctions keep tombstoned rows so [DigestParticipation.Full] still covers
     * them; a literal row delete would break digest reconciliation. No domain
     * hard-deletes (spec correction 2026-07-02).
     *
     * [tombstoneById] applies a firehose `Deleted` frame (id + occurredAt + revision).
     * Only [SoftDelete] carries it, so a [CatchUpOnly] domain can no longer be handed
     * an id-only frame — the old `error("unreachable")` stubs are unrepresentable.
     */
    class SoftDelete(
        val tombstoneById: suspend (id: String, deletedAt: Long, revision: Long) -> Unit,
    ) : DeleteSemantics

    /**
     * Firehose-level `Deleted` is a declared no-op; the tombstone converges on the next
     * catch-up pass. [reason] documents why the id-only frame cannot be applied
     * (e.g. the server id is not a local key). (playback_positions, listening_events,
     * user_stats)
     */
    data class CatchUpOnly(
        val reason: String,
    ) : DeleteSemantics
}
