package com.calypsan.listenup.client.data.sync.domains

/**
 * What a mirrored domain does with an SSE `SyncEvent.Deleted` frame. Catch-up
 * tombstones always route to [MirrorApply.tombstoneFromItem] regardless of this
 * setting (the catch-up item carries the full payload; the SSE frame carries only
 * the server id).
 */
internal sealed interface DeleteSemantics {
    /** Soft-delete the row by id (sets `deletedAt`, keeps the tombstone). Entity domains. */
    data object SoftDelete : DeleteSemantics

    /** Remove the row by id. Junction-row domains (`book_tags`, `collection_books`, …). */
    data object HardDelete : DeleteSemantics

    /**
     * SSE-level `Deleted` is a declared no-op; the tombstone converges on the next
     * catch-up pass. [reason] documents why the id-only frame cannot be applied
     * (e.g. the server id is not a local key). (playback_positions, listening_events,
     * user_stats)
     */
    data class CatchUpOnly(
        val reason: String,
    ) : DeleteSemantics
}
