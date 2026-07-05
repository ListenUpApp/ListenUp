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
 * - [selectIdRevAtMost] — digest slice: `deleted_at IS NULL AND revision <= cursor` (LIVE rows
 *   only; tombstones are excluded so the digest counts the same set the client's tombstone-excluding
 *   digest does — see F1 convergence).
 *
 * **User-scoped variants.** A per-user aggregate (root table carries `user_id`, repo
 * sets `userScoped = true`) additionally implements [selectIdsAboveRevisionForUser] /
 * [selectIdRevAtMostForUser]: the same cursor page / digest slice with an extra
 * `user_id = userId` predicate, so a user's pull/digest never observes another user's
 * rows. They mirror the `AND user_id = ?` the Exposed base appends in
 * [SyncableRepository] for a [UserScopedSyncableTable]. Global aggregates (Tag, Mood)
 * never call them — the default throws — so the global path stays unchanged.
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

    /**
     * User-scoped twin of [selectIdsAboveRevision]: the cursor-forward page filtered to
     * [userId]'s rows. Implemented only by user-scoped aggregates; the default throws so a
     * global aggregate that never wires it can't be silently mis-called.
     */
    fun selectIdsAboveRevisionForUser(
        userId: String,
        cursor: Long,
        limit: Long,
    ): List<IdRev> = error("selectIdsAboveRevisionForUser is only implemented by user-scoped aggregates")

    /**
     * User-scoped twin of [selectIdRevAtMost]: the digest slice filtered to [userId]'s rows.
     * Implemented only by user-scoped aggregates; the default throws so a global aggregate
     * that never wires it can't be silently mis-called.
     */
    fun selectIdRevAtMostForUser(
        userId: String,
        cursor: Long,
    ): List<IdRev> = error("selectIdRevAtMostForUser is only implemented by user-scoped aggregates")
}
