package com.calypsan.listenup.server.sync

/**
 * A (id, revision) pair returned by the revision-cursor substrate queries.
 *
 * Used by the catch-up sync protocol and the digest algorithm: both need a sorted
 * list of entity ids paired with their revision so they can page through changes
 * and compute SHA-256 digests without loading full aggregate payloads.
 */
data class IdRev(
    val id: String,
    val revision: Long,
)

/**
 * Substrate-level queries that every syncable SQLDelight aggregate must provide.
 *
 * The catch-up protocol and digest algorithm in [SyncableRepository] operate on
 * raw `(id, revision)` pairs — they do not need the full aggregate shape.
 * Implementing this interface on the SQLDelight queries wrapper for an aggregate
 * lets the base infrastructure use those pairs without depending on the concrete
 * queries type.
 *
 * Column semantics:
 * - [existsById] — returns `true` if a row (live or soft-deleted) exists with the given id.
 * - [softDeleteById] — stamps `deleted_at`, bumps `revision` and `updated_at`, records
 *   the originating `client_op_id`. Returns the number of rows affected (0 = not found).
 * - [selectIdsAboveRevision] — cursor-forward page: `revision > cursor ORDER BY revision ASC LIMIT limit`.
 * - [selectIdRevAtMost] — digest slice: `revision <= cursor` (all rows, soft-deleted included).
 */
interface SyncableSubstrateQueries {
    fun existsById(id: String): Boolean

    fun softDeleteById(
        id: String,
        revision: Long,
        updatedAt: Long,
        deletedAt: Long,
        clientOpId: String?,
    ): Long

    fun selectIdsAboveRevision(
        cursor: Long,
        limit: Long,
    ): List<IdRev>

    fun selectIdRevAtMost(cursor: Long): List<IdRev>
}
