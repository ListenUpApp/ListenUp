package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [CollectionEntity] sync-substrate operations (Collections — Room v24).
 *
 * Tombstones are soft-deletes: [CollectionEntity.deletedAt] is set to a non-null
 * epoch-ms value when a collection is removed. All observation queries exclude
 * tombstones. `bookCount` is JOIN-derived (no denormalized column) — see
 * [observeAllWithBookCount]. Mirrors [TagDao].
 */
@Dao
internal interface CollectionDao {
    /** Insert or update a collection. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    /** Insert or update multiple collections in one operation. */
    @Upsert
    suspend fun upsertAll(collections: List<CollectionEntity>)

    /** Apply a server tombstone: set [CollectionEntity.deletedAt] and advance [CollectionEntity.revision]. */
    @Query(
        "UPDATE collections SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Retrieve a single non-tombstoned collection by primary key, or null if absent or deleted. */
    @Query("SELECT * FROM collections WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): CollectionEntity?

    /** Observe a single collection by primary key, emitting null when absent or tombstoned. */
    @Query("SELECT * FROM collections WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<CollectionEntity?>

    /**
     * Observe the live collections [bookId] is a live member of — system ones included, so the
     * visibility classifier can tell Public (All Books) and Restricted apart — each row also saying
     * whether the book is held ([HELD_BOOK_IDS_SQL], the inbox's own definition).
     *
     * One statement, so one write is one coherent emission: a release from the inbox into a
     * collection tombstones the INBOX row and adds the new one in a single transaction, and the
     * classifier sees both at once rather than a hold that has lifted over a list that has not yet
     * changed (which read as Public for a frame). No rows means not held: a held book's INBOX
     * membership is itself a live membership of a live collection, so it is always among the rows.
     */
    @Query(
        """
        SELECT c.*, (:bookId IN ($HELD_BOOK_IDS_SQL)) AS isBookHeld FROM collections c
        JOIN collection_books cb ON cb.collectionId = c.id
        WHERE cb.bookId = :bookId AND cb.deletedAt IS NULL AND c.deletedAt IS NULL
    """,
    )
    fun observeHoldingCollections(bookId: String): Flow<List<HoldingCollection>>

    /**
     * Observe all non-tombstoned collections with their live book counts, ordered by name.
     *
     * `bookCount` counts live (non-tombstoned) [CollectionBookEntity] rows per collection
     * via LEFT JOIN — the [GenreDao.observeAllGenresWithBookCount] precedent.
     */
    @Query(
        """
        SELECT c.*, COALESCE(b.cnt, 0) AS bookCount
        FROM collections c
        LEFT JOIN (
            SELECT collectionId, COUNT(*) AS cnt
            FROM collection_books
            WHERE deletedAt IS NULL
            GROUP BY collectionId
        ) b ON b.collectionId = c.id
        WHERE c.deletedAt IS NULL
        ORDER BY c.name ASC
    """,
    )
    fun observeAllWithBookCount(): Flow<List<CollectionWithBookCount>>

    /** Live (non-tombstoned) collection ids — used by the access-change reconcile. */
    @Query("SELECT id FROM collections WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Tombstone the given live collections by id — the chunked access-change prune.
     *
     * Local-only eviction: a collection the caller can no longer see is soft-deleted so the UI
     * drops it; accessible rows are untouched. The existing `revision` is preserved (this is not a
     * server tombstone). The composed handler computes the doomed set in Kotlin and calls this with
     * id chunks bounded under SQLite's bind-var ceiling.
     */
    @Query(
        "UPDATE collections SET deletedAt = :now, updatedAt = :now " +
            "WHERE deletedAt IS NULL AND id IN (:ids)",
    )
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Delete all collection rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM collections")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][CollectionEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM collections WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM collections WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}

/**
 * Room DAO for [CollectionBookEntity] junction sync operations (Collections — Room v24).
 *
 * Soft-deletes are tombstoned via [CollectionBookEntity.deletedAt]; observation queries
 * exclude tombstoned rows so the UI reactively reflects removals. Mirrors [BookTagDao].
 */
@Dao
internal interface CollectionBookDao {
    /** Insert or update a junction row. Replaces on conflict using the composite primary key. */
    @Upsert
    suspend fun upsert(entity: CollectionBookEntity)

    /** Tombstone a junction row: set [CollectionBookEntity.deletedAt] and advance the revision. */
    @Query(
        "UPDATE collection_books SET deletedAt = :deletedAt, revision = :revision " +
            "WHERE collectionId = :collectionId AND bookId = :bookId",
    )
    suspend fun tombstone(
        collectionId: String,
        bookId: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Tombstone a junction row by its opaque wire [syncId] (SERVER-SYNC-04) — the by-identity
     * counterpart to [tombstone], used when applying a `SyncEvent.Deleted` frame whose payload
     * has its natural pair blanked (junction tombstones ship identity only). Returns the number
     * of rows affected (0 when [syncId] matches no local row — a graceful no-op the caller logs,
     * since there is no longer a composite id to parse and fail on).
     */
    @Query("UPDATE collection_books SET deletedAt = :deletedAt, revision = :revision WHERE syncId = :syncId")
    suspend fun tombstoneBySyncId(
        syncId: String,
        deletedAt: Long,
        revision: Long,
    ): Int

    /** Return the junction row for the given [collectionId]/[bookId] pair, or null if absent. */
    @Query("SELECT * FROM collection_books WHERE collectionId = :collectionId AND bookId = :bookId LIMIT 1")
    suspend fun findByKey(
        collectionId: String,
        bookId: String,
    ): CollectionBookEntity?

    /**
     * Cascade-tombstone every live junction row for [collectionId] — the client mirror of the
     * server's `deleteCollection` cascade (soft-delete all `collection_books` for the collection).
     * Advances each row's revision so the server's own cascade echo (at least one higher) still
     * applies through the revision guard. Mirrors [BookTagDao.tombstoneAllForTag].
     */
    @Query(
        "UPDATE collection_books SET deletedAt = :deletedAt, revision = revision + 1 " +
            "WHERE collectionId = :collectionId AND deletedAt IS NULL",
    )
    suspend fun tombstoneAllForCollection(
        collectionId: String,
        deletedAt: Long,
    )

    /** The live (non-tombstoned) book ids of a collection, one-shot — the books a cascade delete touches. */
    @Query("SELECT bookId FROM collection_books WHERE collectionId = :collectionId AND deletedAt IS NULL")
    suspend fun liveBookIdsFor(collectionId: String): List<String>

    /** Live (non-tombstoned) book count for a single collection — used by the offline-first rename's optimistic return. */
    @Query("SELECT COUNT(*) FROM collection_books WHERE collectionId = :collectionId AND deletedAt IS NULL")
    suspend fun liveBookCountFor(collectionId: String): Int

    /** Observe the live (non-tombstoned) book ids for a collection. */
    @Query(
        "SELECT bookId FROM collection_books WHERE collectionId = :collectionId AND deletedAt IS NULL ORDER BY createdAt ASC",
    )
    fun observeBookIds(collectionId: String): Flow<List<String>>

    /** Observe the live (non-tombstoned) collection ids a book currently belongs to. */
    @Query(
        "SELECT collectionId FROM collection_books WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt ASC",
    )
    fun observeCollectionIdsForBook(bookId: String): Flow<List<String>>

    /**
     * Observe the books held for review, oldest hold first — see [HELD_BOOK_IDS_SQL].
     *
     * The single source the inbox page, the Library entry, the navigation badge and the Book Detail
     * held section all read, so they cannot disagree. Re-emits on any change to `collection_books`
     * or `collections`. Always empty on a member's device.
     */
    @Query("$HELD_BOOK_IDS_SQL ORDER BY held_cb.createdAt ASC")
    fun observeHeldBookIds(): Flow<List<String>>

    /** One-shot counterpart to [observeHeldBookIds], for reads that are themselves one-shot (shelf detail). */
    @Query(HELD_BOOK_IDS_SQL)
    suspend fun heldBookIds(): List<String>

    /**
     * Observe the ids of restricted books: a live membership in a live **normal** collection
     * (neither system nor inbox), excluding every held book ([HELD_BOOK_IDS_SQL]) — so this set is
     * exactly the books the visibility classifier calls Restricted, and the lock never meets the
     * *Held* marker on a card. Drives the admin-only lock on every book card; the repository gates
     * it to admins.
     */
    @Query(
        """
        SELECT DISTINCT cb.bookId FROM collection_books cb
        JOIN collections c ON c.id = cb.collectionId
        WHERE cb.deletedAt IS NULL AND c.deletedAt IS NULL AND c.isSystem = 0 AND c.isInbox = 0
          AND cb.bookId NOT IN ($HELD_BOOK_IDS_SQL)
    """,
    )
    fun observeRestrictedBookIds(): Flow<List<String>>

    /**
     * Tombstone the INBOX memberships of [bookIds] — the write-through after the server has
     * committed a release, so the books leave the inbox, the badge and the library's exclusion the
     * moment the RPC succeeds rather than when the echo lands.
     *
     * Local-only, like [tombstoneByIds]: the existing `revision` is preserved, so the server's own
     * tombstone echo (a higher revision) still applies through the revision guard. The revision is
     * kept deliberately — this write has no outbox op, so resetting it (say to 0) would let any older
     * frame resurrect the row. The cost: a catch-up page already in flight at the same revision can
     * briefly restore it, and the server's tombstone (R+1) converges it.
     *
     * Rows are selected by [HELD_MEMBERSHIPS_SQL], so only the INBOX memberships end — any other
     * membership of the same book is left alone.
     */
    @Query(
        "UPDATE collection_books SET deletedAt = :now " +
            "WHERE bookId IN (:bookIds) AND rowid IN (SELECT held_cb.rowid $HELD_MEMBERSHIPS_SQL)",
    )
    suspend fun tombstoneHeldRows(
        bookIds: List<String>,
        now: Long,
    )

    /**
     * The live collection ids [bookId] is a live **normal** member of — every membership except the
     * system ones (All Books, Inbox), which the server manages and a client never names. A membership
     * whose collection row has not synced counts as normal: nothing says it is a system one.
     *
     * The diff base of the offline-first `setBookCollections`, mirroring the server's diff, which
     * also subtracts the system ids before comparing.
     */
    @Query(
        """
        SELECT cb.collectionId FROM collection_books cb
        LEFT JOIN collections c ON c.id = cb.collectionId
        WHERE cb.bookId = :bookId AND cb.deletedAt IS NULL
          AND COALESCE(c.isSystem, 0) = 0 AND COALESCE(c.isInbox, 0) = 0
        ORDER BY cb.createdAt ASC
    """,
    )
    suspend fun liveNormalCollectionIdsForBook(bookId: String): List<String>

    /**
     * Whether [bookId] has a live membership in a live **normal** collection. As in
     * [liveNormalCollectionIdsForBook], a membership whose collection row has not synced counts as
     * normal — nothing says it is a system one — so the reconcile errs towards keeping a book out of
     * All Books, never towards showing it to everyone.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM collection_books cb
            LEFT JOIN collections c ON c.id = cb.collectionId
            WHERE cb.bookId = :bookId AND cb.deletedAt IS NULL AND c.deletedAt IS NULL
              AND COALESCE(c.isSystem, 0) = 0 AND COALESCE(c.isInbox, 0) = 0
        )
    """,
    )
    suspend fun hasLiveNormalMembership(bookId: String): Boolean

    /** Whether [bookId] is held for review, once — [HELD_BOOK_IDS_SQL], the inbox's own definition. */
    @Query("SELECT :bookId IN ($HELD_BOOK_IDS_SQL)")
    suspend fun isHeld(bookId: String): Boolean

    /**
     * The id of the live All Books collection of [bookId]'s own library, or null when the book or
     * that collection has not synced. All Books is the system collection that is not the inbox.
     */
    @Query(
        """
        SELECT c.id FROM collections c
        JOIN books b ON b.libraryId = c.libraryId
        WHERE b.id = :bookId AND c.isSystem = 1 AND c.isInbox = 0 AND c.deletedAt IS NULL
        LIMIT 1
    """,
    )
    suspend fun allBooksCollectionIdForBook(bookId: String): String?

    /**
     * Bring back a tombstoned junction row as live, one revision ahead of the tombstone it replaces —
     * see `SystemMembershipReconciler` for why one ahead. A live or absent row is left alone.
     */
    @Query(
        "UPDATE collection_books SET deletedAt = NULL, revision = revision + 1 " +
            "WHERE collectionId = :collectionId AND bookId = :bookId AND deletedAt IS NOT NULL",
    )
    suspend fun reviveLocally(
        collectionId: String,
        bookId: String,
    )

    /**
     * Tombstone [bookId]'s live All Books memberships, keeping each row's revision — see
     * `SystemMembershipReconciler` for why this one, unlike [reviveLocally], does not move ahead.
     */
    @Query(
        "UPDATE collection_books SET deletedAt = :now " +
            "WHERE bookId = :bookId AND deletedAt IS NULL AND collectionId IN " +
            "(SELECT id FROM collections WHERE isSystem = 1 AND isInbox = 0)",
    )
    suspend fun tombstoneAllBooksRowsLocally(
        bookId: String,
        now: Long,
    )

    /**
     * Live (non-tombstoned) junction ids — the opaque wire [CollectionBookEntity.syncId] values
     * (SERVER-SYNC-04), used by the access-change reconcile so the local set lines up with
     * `catchUpTransient`'s returned set.
     */
    @Query("SELECT syncId FROM collection_books WHERE deletedAt IS NULL")
    suspend fun liveSyncIds(): List<String>

    /**
     * Live (non-tombstoned) junction [CollectionBookEntity.syncId] values whose [collectionId] is
     * one of [collectionIds] — the scoped `AccessDeltaPolicy.Targeted` candidate set. Replaces the
     * pre-SERVER-SYNC-04 `liveSyncIds().filter { it.substringBefore(':') in scopeCols }` trick: the
     * opaque wire id no longer encodes [collectionId], so the scope filter must be a real column
     * predicate instead of string-splitting the id.
     */
    @Query("SELECT syncId FROM collection_books WHERE collectionId IN (:collectionIds) AND deletedAt IS NULL")
    suspend fun liveSyncIdsForCollections(collectionIds: List<String>): List<String>

    /**
     * Tombstone the given live junction rows by opaque wire [CollectionBookEntity.syncId] — the
     * chunked access-change prune.
     *
     * Local-only eviction. The existing `revision` is preserved (this is not a server tombstone).
     * The composed handler computes the doomed set in Kotlin and calls this with id chunks bounded
     * under SQLite's bind-var ceiling.
     */
    @Query(
        "UPDATE collection_books SET deletedAt = :now " +
            "WHERE deletedAt IS NULL AND syncId IN (:ids)",
    )
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Delete all junction rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM collection_books")
    suspend fun deleteAll()

    /**
     * All rows (including tombstones) with [revision][CollectionBookEntity.revision] <= [max], for digest computation.
     *
     * The id is the opaque wire [CollectionBookEntity.syncId] (SERVER-SYNC-04) — the same value
     * the server uses on the wire.
     */
    @Query(
        "SELECT syncId AS id, revision FROM collection_books WHERE deletedAt IS NULL AND revision <= :max",
    )
    suspend fun digestRows(max: Long): List<IdRevision>

    /**
     * The stored revision of the junction row for [collectionId]/[bookId], tombstones included;
     * null when the row has never been seen.
     */
    @Query("SELECT revision FROM collection_books WHERE collectionId = :collectionId AND bookId = :bookId LIMIT 1")
    suspend fun revisionOf(
        collectionId: String,
        bookId: String,
    ): Long?

    /**
     * The stored revision of the junction row with opaque wire [syncId] (SERVER-SYNC-04),
     * tombstones included; null when the row has never been seen. The by-identity counterpart to
     * [revisionOf], used by the [ConflictPolicy.ServerWins] guard now that the wire id no longer
     * decomposes into the natural pair.
     */
    @Query("SELECT revision FROM collection_books WHERE syncId = :syncId LIMIT 1")
    suspend fun revisionOfSyncId(syncId: String): Long?
}

/**
 * Room DAO for [CollectionShareEntity] sync operations (Collections — Room v24).
 *
 * Soft-deletes via [CollectionShareEntity.deletedAt] represent revoked shares;
 * observation queries exclude tombstoned rows. Mirrors [TagDao].
 */
@Dao
internal interface CollectionShareDao {
    /** Insert or update a share. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(share: CollectionShareEntity)

    /** Apply a server tombstone: set [CollectionShareEntity.deletedAt] and advance the revision. */
    @Query(
        "UPDATE collection_shares SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Retrieve a single non-tombstoned share by primary key, or null if absent or deleted. */
    @Query("SELECT * FROM collection_shares WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): CollectionShareEntity?

    /** Observe live (non-tombstoned) shares for a collection, ordered by recipient. */
    @Query(
        "SELECT * FROM collection_shares WHERE collectionId = :collectionId AND deletedAt IS NULL ORDER BY sharedWithUserId ASC",
    )
    fun observeForCollection(collectionId: String): Flow<List<CollectionShareEntity>>

    /**
     * Observe every live share. Deliberately not narrowed to one book: a query that joined
     * `collection_books` would re-run on a membership write separately from the book's membership
     * read, and their combine could pair the new memberships with the old shares for a frame. The
     * classifier keeps only shares of the book's live normal collections.
     */
    @Query("SELECT * FROM collection_shares WHERE deletedAt IS NULL")
    fun observeLive(): Flow<List<CollectionShareEntity>>

    /** Live (non-tombstoned) share ids — used by the access-change reconcile. */
    @Query("SELECT id FROM collection_shares WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Tombstone the given live shares by id — the chunked access-change prune.
     *
     * Local-only eviction. The existing `revision` is preserved (this is not a server tombstone).
     * The composed handler computes the doomed set in Kotlin and calls this with id chunks bounded
     * under SQLite's bind-var ceiling.
     */
    @Query(
        "UPDATE collection_shares SET deletedAt = :now, updatedAt = :now " +
            "WHERE deletedAt IS NULL AND id IN (:ids)",
    )
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /** Delete all share rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM collection_shares")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][CollectionShareEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM collection_shares WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM collection_shares WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
