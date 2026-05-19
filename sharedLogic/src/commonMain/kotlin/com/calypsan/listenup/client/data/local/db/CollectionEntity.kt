package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.calypsan.listenup.core.Timestamp

/**
 * Room entity representing a collection in the local database.
 *
 * Collections are admin-only features that group books for organizational purposes.
 * They are synced from the server and cached locally for quick access.
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val bookCount: Int,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val syncState: SyncState = SyncState.SYNCED,
    val serverVersion: Timestamp = Timestamp(0),
)
