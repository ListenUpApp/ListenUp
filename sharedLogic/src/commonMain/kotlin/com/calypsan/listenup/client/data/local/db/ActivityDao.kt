package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ActivityEntity] operations.
 *
 * Provides reactive (Flow-based) and one-shot queries for activity feed.
 * Activities are stored locally for offline-first display.
 */
@Dao
internal interface ActivityDao {
    /**
     * Observe the newest [limit] live activities, fully enriched at READ time by LEFT-JOINing the
     * local `public_profiles` mirror (identity) and the `books` mirror (book card: title, cover,
     * primary author). All enrichment is in SQL, so the Flow re-emits whenever `activities`,
     * `public_profiles`, `books`, `book_contributors`, or `contributors` change — a rename of the
     * user OR the book/author reflects automatically, and there is no per-row N+1. A book that is
     * absent locally (inaccessible) or tombstoned yields a null card. Activity tombstones excluded.
     */
    @Query(
        """
        SELECT a.id, a.userId, a.type, a.occurredAt, a.bookId, a.isReread, a.durationMs,
               a.milestoneValue, a.milestoneUnit, a.shelfId, a.shelfName,
               pp.displayName AS displayName, pp.avatarType AS avatarType,
               b.title AS bookTitle, b.coverBlurHash AS bookCoverPath,
               (
                   SELECT c.name FROM book_contributors bc
                   INNER JOIN contributors c ON bc.contributorId = c.id
                   WHERE bc.bookId = b.id AND bc.role = 'author' LIMIT 1
               ) AS bookAuthorName
        FROM activities a
        LEFT JOIN public_profiles pp ON pp.id = a.userId
        LEFT JOIN books b ON b.id = a.bookId AND b.deletedAt IS NULL
        WHERE a.deletedAt IS NULL
        ORDER BY a.occurredAt DESC
        LIMIT :limit
    """,
    )
    fun observeRecent(limit: Int): Flow<List<ActivityWithProfile>>

    /** Read a single activity row (tombstone-inclusive) — the mirror's insert-if-absent probe. */
    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getById(id: String): ActivityEntity?

    /**
     * Advance only the revision of an existing row (append-only re-apply). Domain fields are never
     * mutated, but converging the revision when the server re-upserts an id (idempotent replay /
     * backfill) keeps the `(id, revision)` digest from permanently drifting on that client.
     */
    @Query("UPDATE activities SET revision = :revision WHERE id = :id")
    suspend fun updateRevision(
        id: String,
        revision: Long,
    )

    /** Soft-delete (tombstone) an activity by id — the mirror's catch-up tombstone path. */
    @Query("UPDATE activities SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Resurrect a locally-tombstoned activity: clear its tombstone and align its revision. Used when
     * a row the access-gate prune soft-deleted is later re-delivered LIVE (a restored share re-sends
     * it via catch-up with `deletedAt = null`). `deletedAt` is sync substrate, not append-only content,
     * so it may flip back to null — without this the row would stay tombstoned forever and, because the
     * server digest and the client's tombstone-inclusive digest then agree on `(id, revision)`, no
     * reconcile could ever heal it.
     */
    @Query("UPDATE activities SET deletedAt = NULL, revision = :revision WHERE id = :id")
    suspend fun restore(
        id: String,
        revision: Long,
    )

    /** All rows (including tombstones) with revision <= max, for digest computation. */
    @Query("SELECT id AS id, revision FROM activities WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** Ids of every live (non-tombstoned) activity — the access-gate's local-live set. */
    @Query("SELECT id FROM activities WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Tombstone the given live activities by id — the chunked access-gate prune. `activities` is
     * append-forever (one row per listening session, every user), so the doomed set can exceed
     * SQLite's ~32k bind-variable ceiling; the gate computes the doomed set in Kotlin and calls this
     * with id chunks bounded well under the limit. A bounded `IN (:ids)` never overflows the binder.
     */
    @Query("UPDATE activities SET deletedAt = :now WHERE deletedAt IS NULL AND id IN (:ids)")
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /**
     * Soft-delete every live activity whose gating book is gone or tombstoned — the activities half of
     * the books access-gate `afterPrune` cascade. A scoped `AccessChanged` delta only fetches the
     * `books`/`collections`/`collection_books` domains, so an activity whose book was just revoked
     * would otherwise linger live locally while the server's access-filtered activities digest no
     * longer counts it → permanent digest drift. Tombstoning it here (soft, like [tombstoneByIds] —
     * `activities` is a cursored domain, so a hard delete would itself drift the digest) converges the
     * two sides; a re-grant re-delivers the row live via catch-up. Book-less activities (`bookId IS
     * NULL`) are always visible and are left untouched.
     */
    @Query(
        "UPDATE activities SET deletedAt = :now " +
            "WHERE deletedAt IS NULL AND bookId IS NOT NULL " +
            "AND bookId NOT IN (SELECT id FROM books WHERE deletedAt IS NULL)",
    )
    suspend fun tombstoneWhereBookNotLive(now: Long)

    /**
     * Insert or update an activity entity.
     * If an activity with the same ID exists, it will be updated.
     *
     * @param activity The activity entity to upsert
     */
    @Upsert
    suspend fun upsert(activity: ActivityEntity)

    /**
     * Count total activities (tombstones included) — the local mirror size. NOTE: hard-DELETE queries
     * were intentionally removed. `activities` is a cursored MirroredDomain; a hard delete would drop
     * a row the server still counts in the member's digest → permanent, non-converging drift.
     * Removal is soft-delete only (tombstone via [softDelete] / the access-gate prune).
     */
    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int
}

/**
 * A live activity row joined to its author's `public_profiles` identity — the read-time enrichment
 * projection for the feed. Profile fields are nullable (LEFT JOIN: the author's profile may not be
 * mirrored yet); the repository falls back to a placeholder and derives the avatar colour locally.
 */
internal data class ActivityWithProfile(
    val id: String,
    val userId: String,
    val type: String,
    val occurredAt: Long,
    val bookId: String?,
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
    val displayName: String?,
    val avatarType: String?,
    val bookTitle: String?,
    val bookCoverPath: String?,
    val bookAuthorName: String?,
)
