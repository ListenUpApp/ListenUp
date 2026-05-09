package com.calypsan.listenup.api.sync

/**
 * Marker interface for syncable domain DTOs that carry the canonical
 * sync-discipline `deletedAt: Long?` field.
 *
 * Tombstone routing is a wire-protocol concern, not a per-domain switch — the
 * REST catch-up loop and any other consumer that needs to distinguish "current
 * row" from "soft-delete sentinel" looks at `deletedAt` alone. Implementing
 * this interface on a DTO opts that DTO into the generic routing path.
 *
 * Lives alongside the DTOs in `api/sync/` because it's part of the wire
 * contract — every DTO that ships through the sync substrate (tag, book,
 * contributor, series, etc.) implements it.
 */
interface Tombstoned {
    /** Server-set soft-delete timestamp (epoch millis). `null` for current rows. */
    val deletedAt: Long?
}
