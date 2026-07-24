package com.calypsan.listenup.client.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing one root filesystem path registered under a parent [LibraryEntity].
 *
 * A library may have N folders. Each folder carries the absolute server-side path to its
 * root directory. Carries the sync substrate ([revision], [deletedAt]) so catch-up and
 * firehose sync paths can apply tombstones uniformly.
 *
 * The foreign key cascades deletes: removing a library hard-removes its folder rows.
 * The index on [libraryId] accelerates [LibraryFolderDao.observeForLibrary] and
 * [LibraryFolderDao.findAllForLibrary].
 */
@Entity(
    tableName = "library_folders",
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["libraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("libraryId")],
)
internal data class LibraryFolderEntity(
    @PrimaryKey val id: String,
    /** Parent library identifier. Foreign key to [LibraryEntity.id]. */
    val libraryId: String,
    /** Absolute server-side filesystem path to this folder's root directory. */
    val rootPath: String,
    /** Creation timestamp as Unix epoch milliseconds. */
    val createdAt: Long,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long,
    /** Epoch ms tombstone; null when the folder is live. */
    val deletedAt: Long?,
)
