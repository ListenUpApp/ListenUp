package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.calypsan.listenup.api.sync.WorldEventType
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the `world_events` sync domain — the Story World events the signed-in user can see. Exactly
 * one of [homeSeriesId] / [homeBookId] is set on a live row (a tombstone may carry neither). [type] goes
 * through [WorldEventTypeConverter], so a type this build doesn't know reads as UNKNOWN.
 */
@Entity(
    tableName = "world_events",
    indices = [
        Index(value = ["homeSeriesId"]),
        Index(value = ["homeBookId"]),
        Index(value = ["bookId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class WorldEventEntity(
    @PrimaryKey val id: String,
    val homeSeriesId: String? = null,
    val homeBookId: String? = null,
    /** The anchor book; set together with [positionMs], or neither. */
    val bookId: String? = null,
    val positionMs: Long? = null,
    val type: WorldEventType,
    val text: String = "",
    val detail: String? = null,
    val subjectEntityId: String? = null,
    val objectEntityId: String? = null,
    val createdBy: String? = null,
    val updatedBy: String? = null,
    /** Server revision; 0 for a local row not yet confirmed. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when live. */
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One entity a [WorldEventEntity] names. The server's set once synced; the client's own guess before that. */
@Entity(
    tableName = "world_event_mentions",
    primaryKeys = ["eventId", "entityId"],
    indices = [Index(value = ["entityId"])],
)
internal data class WorldEventMentionEntity(
    val eventId: String,
    val entityId: String,
)

/** A [WorldEventEntity] with the ids it mentions. */
internal data class WorldEventWithMentions(
    @Embedded val event: WorldEventEntity,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["eventId"],
        entity = WorldEventMentionEntity::class,
        projection = ["entityId"],
    )
    val mentionIds: List<String>,
)

/** Room DAO for [WorldEventEntity] and its mentions. Observation excludes tombstones; [findById] includes them. */
@Dao
internal interface WorldEventDao {
    @Upsert
    suspend fun upsert(event: WorldEventEntity)

    @Upsert
    suspend fun upsertMentions(mentions: List<WorldEventMentionEntity>)

    @Query("DELETE FROM world_event_mentions WHERE eventId = :eventId")
    suspend fun deleteMentionsFor(eventId: String)

    /** The ids [eventId] mentions, sorted. */
    @Query("SELECT entityId FROM world_event_mentions WHERE eventId = :eventId ORDER BY entityId")
    suspend fun mentionIdsFor(eventId: String): List<String>

    /** The row with [id], tombstoned or not. */
    @Query("SELECT * FROM world_events WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): WorldEventEntity?

    /** The live row with [id]. */
    @Query("SELECT * FROM world_events WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): WorldEventEntity?

    /** Observes the live event [id]; emits null once it is tombstoned or gone. */
    @Transaction
    @Query("SELECT * FROM world_events WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<WorldEventWithMentions?>

    /** Observes the live events homed on [seriesId], oldest first. */
    @Transaction
    @Query("SELECT * FROM world_events WHERE homeSeriesId = :seriesId AND deletedAt IS NULL ORDER BY createdAt, id")
    fun observeForSeries(seriesId: String): Flow<List<WorldEventWithMentions>>

    /** Observes the live events homed on [bookId], oldest first. */
    @Transaction
    @Query("SELECT * FROM world_events WHERE homeBookId = :bookId AND deletedAt IS NULL ORDER BY createdAt, id")
    fun observeForBook(bookId: String): Flow<List<WorldEventWithMentions>>

    /** Observes the live events anchored to [bookId], in book order. */
    @Transaction
    @Query("SELECT * FROM world_events WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY positionMs, createdAt, id")
    fun observeAnchoredTo(bookId: String): Flow<List<WorldEventWithMentions>>

    /** Observes the live events that mention [entityId], oldest first. */
    @Transaction
    @Query(
        "SELECT * FROM world_events WHERE deletedAt IS NULL AND " +
            "id IN (SELECT eventId FROM world_event_mentions WHERE entityId = :entityId) ORDER BY createdAt, id",
    )
    fun observeMentioning(entityId: String): Flow<List<WorldEventWithMentions>>

    /** Tombstone [id], keeping [revision] so the server's tombstone echo still applies through the guard. */
    @Query(
        "UPDATE world_events SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Live (id, revision) pairs at or below [max] — the digest's local half. */
    @Query("SELECT id AS id, revision FROM world_events WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of [id], tombstoned or not; null when there is no row. */
    @Query("SELECT revision FROM world_events WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?

    /** Live ids — the access gate's local truth set. */
    @Query("SELECT id FROM world_events WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /** Tombstone live rows by id — the access-change prune. */
    @Query("UPDATE world_events SET deletedAt = :now WHERE deletedAt IS NULL AND id IN (:ids)")
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /**
     * Live events a change to the asked-about books can affect: homed on one, anchored to one, or homed on a
     * series that holds one. The three lists are the same ids, bound three times. The scoped access delta's
     * candidate set.
     */
    @Query(
        "SELECT id FROM world_events WHERE deletedAt IS NULL AND (homeBookId IN (:homeBookIds) OR " +
            "bookId IN (:anchorBookIds) OR homeSeriesId IN (SELECT seriesId FROM book_series WHERE bookId IN (:seriesBookIds)))",
    )
    suspend fun liveIdsTouchingBooks(
        homeBookIds: List<String>,
        anchorBookIds: List<String>,
        seriesBookIds: List<String>,
    ): List<String>

    /** Empties the mirror's events. */
    @Query("DELETE FROM world_events")
    suspend fun deleteAll()

    /** Empties the mirror's mentions. */
    @Query("DELETE FROM world_event_mentions")
    suspend fun deleteAllMentions()
}

/**
 * Replaces [eventId]'s mention rows with [entityIds]. Callers run it inside their transaction — the mirror's
 * apply, or an `OfflineEditor` edit — so an event and its mentions always change together.
 */
internal suspend fun WorldEventDao.replaceMentions(
    eventId: String,
    entityIds: Collection<String>,
) {
    deleteMentionsFor(eventId)
    if (entityIds.isNotEmpty()) {
        upsertMentions(entityIds.distinct().map { WorldEventMentionEntity(eventId = eventId, entityId = it) })
    }
}
