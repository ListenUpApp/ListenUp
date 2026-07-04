package com.calypsan.listenup.api.sync

/**
 * The wire contract every mirrored sync payload satisfies: a stable [id], a monotone
 * [revision], and (via [Tombstoned]) a soft-delete `deletedAt` sentinel. These are the
 * three fields the sync substrate reads generically — identity for envelope matching,
 * revision for staleness guarding, `deletedAt` for tombstone routing — so declaring
 * them once here lets the descriptor default `syncIdOf` to `{ it.id }` and lets the
 * revision guard read `payload.revision` directly, with no per-domain lambda.
 *
 * Composite-key junctions (`book_tags`, `book_moods`, `collection_books`) whose wire
 * form omits a single `id` column expose it as a computed `"$a:$b"` property matching
 * the server's synthetic envelope id — the same synthetic id `shelf_books` carries as a
 * real field. Because the computed property is not a primary-constructor parameter it
 * is never serialized, so implementing this interface leaves the wire format untouched.
 */
interface SyncPayload : Tombstoned {
    /** Stable sync identity — matches the SSE envelope id and the catch-up cursor key. */
    val id: String

    /** Global revision at write time; strictly increases on every server write. */
    val revision: Long
}
