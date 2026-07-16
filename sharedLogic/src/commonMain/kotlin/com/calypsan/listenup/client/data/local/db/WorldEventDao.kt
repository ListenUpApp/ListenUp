package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [WorldEventEntity] / [WorldEventMentionEntity] sync-substrate operations (Story
 * World unified event log).
 *
 * Tombstones are soft-deletes: [WorldEventEntity.deletedAt] is set to a non-null epoch-ms value
 * when an event is removed. All observation queries exclude tombstones. Mirrors [EntityDao],
 * plus the [WorldEventMentionEntity] junction every read hydrates and every write replaces.
 */
@Dao
internal interface WorldEventDao {
    /** Insert or update an event's root row. Replaces on conflict using the primary key. */
    @Upsert
    suspend fun upsert(event: WorldEventEntity)

    /**
     * Retrieve a single non-tombstoned event by id, with no mentions hydrated — used by write
     * paths (repository update/delete) that only need the row's own columns for a
     * read-modify-write, not the mention set.
     */
    @Query("SELECT * FROM world_events WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): WorldEventEntity?

    /** Observe a single event with its mention set, emitting null when absent or tombstoned. */
    @Transaction
    @Query("SELECT * FROM world_events WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<WorldEventWithMentions?>

    /** Observe every non-tombstoned event anchored to [bookId], ordered by its book position. */
    @Transaction
    @Query("SELECT * FROM world_events WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY positionMs ASC")
    fun observeForBook(bookId: String): Flow<List<WorldEventWithMentions>>

    /**
     * Observe every non-tombstoned event that mentions [entityId] — as its subject, its object, or
     * an inline `@entity` token in its text — via the [WorldEventMentionEntity] junction, mirroring
     * the server's `selectLiveByEntity` subquery.
     */
    @Transaction
    @Query(
        "SELECT * FROM world_events WHERE deletedAt IS NULL AND " +
            "id IN (SELECT eventId FROM world_event_mentions WHERE entityId = :entityId) " +
            "ORDER BY updatedAt DESC",
    )
    fun observeForEntity(entityId: String): Flow<List<WorldEventWithMentions>>

    /**
     * Observe every non-tombstoned event namespaced under exactly one of [homeSeriesId] /
     * [homeBookId], mirroring the server's `listForWorld` dual-home dispatch in one query — when
     * both are null (or both non-null, which the write path never allows to persist) this returns
     * empty rather than guessing.
     */
    @Transaction
    @Query(
        "SELECT * FROM world_events WHERE deletedAt IS NULL AND " +
            "((:homeSeriesId IS NOT NULL AND homeSeriesId = :homeSeriesId) OR " +
            "(:homeBookId IS NOT NULL AND homeBookId = :homeBookId)) " +
            "ORDER BY updatedAt DESC",
    )
    fun observeForWorld(
        homeSeriesId: String?,
        homeBookId: String?,
    ): Flow<List<WorldEventWithMentions>>

    /** Insert mention rows. Part of [replaceMentions]'s delete-then-insert pair. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMentions(mentions: List<WorldEventMentionEntity>)

    /** Delete every mention row for [eventId]. Part of [replaceMentions]'s delete-then-insert pair. */
    @Query("DELETE FROM world_event_mentions WHERE eventId = :eventId")
    suspend fun deleteMentionsForEvent(eventId: String)

    /**
     * Raw mention rows for [eventId] — tombstone-oblivious (reads the junction directly, not
     * filtered through the parent event's `deletedAt`). Verification-only: proves
     * [replaceMentions] / a tombstone's junction cleanup actually touched the table, which the
     * tombstone-filtered `observeFor*` queries can't distinguish from "the parent row is merely
     * hidden."
     */
    @Query("SELECT * FROM world_event_mentions WHERE eventId = :eventId")
    suspend fun mentionsForEventRaw(eventId: String): List<WorldEventMentionEntity>

    /**
     * Replace [eventId]'s mention set wholesale — delete-then-insert, the
     * [GenreDao.replaceGenresForBook] precedent. Every production call site (mirror-apply, the
     * offline editor's optimistic write) already runs inside an outer write transaction, so this
     * method's own `@Transaction` only matters for call sites outside that (tests).
     */
    @Transaction
    suspend fun replaceMentions(
        eventId: String,
        entityIds: Collection<String>,
    ) {
        deleteMentionsForEvent(eventId)
        if (entityIds.isNotEmpty()) {
            insertMentions(entityIds.map { WorldEventMentionEntity(eventId = eventId, entityId = it) })
        }
    }

    /** Apply a server tombstone: set [WorldEventEntity.deletedAt] and advance [WorldEventEntity.revision]. */
    @Query(
        "UPDATE world_events SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Delete all event rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM world_events")
    suspend fun deleteAll()

    /** Delete all mention rows (used in tests and full re-sync scenarios). */
    @Query("DELETE FROM world_event_mentions")
    suspend fun deleteAllMentions()

    /** All rows (including tombstones) with [revision][WorldEventEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM world_events WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM world_events WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?
}
