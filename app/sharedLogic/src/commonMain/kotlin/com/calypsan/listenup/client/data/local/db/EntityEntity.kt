package com.calypsan.listenup.client.data.local.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import com.calypsan.listenup.api.sync.EntityKind
import kotlinx.coroutines.flow.Flow

/**
 * Room mirror of the `entities` sync domain — Story World entities the signed-in user can see. Exactly one
 * of [homeSeriesId] / [homeBookId] is set on a live row (a tombstone may carry neither). [kind] goes through
 * [EntityKindConverter], so a kind this build doesn't know reads as [EntityKind.UNKNOWN] instead of failing
 * the query.
 */
@Entity(
    tableName = "entities",
    indices = [
        Index(value = ["homeSeriesId"]),
        Index(value = ["homeBookId"]),
        Index(value = ["parentId"]),
        Index(value = ["deletedAt"]),
    ],
)
internal data class EntityEntity(
    @PrimaryKey val id: String,
    val kind: EntityKind,
    val name: String,
    val descriptor: String? = null,
    val parentId: String? = null,
    val homeSeriesId: String? = null,
    val homeBookId: String? = null,
    val imageRef: String? = null,
    val createdBy: String? = null,
    val updatedBy: String? = null,
    /** Server revision; 0 for a local row not yet confirmed. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when live. */
    val deletedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Room DAO for [EntityEntity]. Observation excludes tombstones; [findById] includes them (undo needs them). */
@Dao
internal interface EntityDao {
    @Upsert
    suspend fun upsert(entity: EntityEntity)

    /** The row with [id], tombstoned or not. */
    @Query("SELECT * FROM entities WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): EntityEntity?

    /** The live row with [id]. */
    @Query("SELECT * FROM entities WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: String): EntityEntity?

    /** Observes the live row with [id]; emits null once it is tombstoned or gone. */
    @Query("SELECT * FROM entities WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeById(id: String): Flow<EntityEntity?>

    /** Observes the live entities homed on [seriesId], by name. */
    @Query("SELECT * FROM entities WHERE homeSeriesId = :seriesId AND deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeForSeries(seriesId: String): Flow<List<EntityEntity>>

    /** Observes the live entities homed on [bookId], by name. */
    @Query("SELECT * FROM entities WHERE homeBookId = :bookId AND deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeForBook(bookId: String): Flow<List<EntityEntity>>

    /** Tombstone [id], keeping [revision] so the server's tombstone echo still applies through the guard. */
    @Query("UPDATE entities SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /** Live (id, revision) pairs at or below [max] — the digest's local half. */
    @Query("SELECT id AS id, revision FROM entities WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of [id], tombstoned or not; null when there is no row. */
    @Query("SELECT revision FROM entities WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?

    /** Live ids — the access gate's local truth set. */
    @Query("SELECT id FROM entities WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /** Tombstone live rows by id — the access-change prune. */
    @Query("UPDATE entities SET deletedAt = :now WHERE deletedAt IS NULL AND id IN (:ids)")
    suspend fun tombstoneByIds(
        ids: List<String>,
        now: Long,
    )

    /**
     * Live entities a change to [bookIds] can affect: those homed on one of the books, and those homed on a
     * series that contains one ([seriesBookIds] is the same list, bound twice). The scoped access delta's
     * candidate set.
     */
    @Query(
        "SELECT id FROM entities WHERE deletedAt IS NULL AND (homeBookId IN (:bookIds) OR homeSeriesId IN " +
            "(SELECT seriesId FROM book_series WHERE bookId IN (:seriesBookIds)))",
    )
    suspend fun liveIdsTouchingBooks(
        bookIds: List<String>,
        seriesBookIds: List<String>,
    ): List<String>

    /** Empties the mirror. */
    @Query("DELETE FROM entities")
    suspend fun deleteAll()
}
