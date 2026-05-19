package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.calypsan.listenup.core.Timestamp

/**
 * Room entity representing a shelf in the local database.
 *
 * Shelves are user-created curated lists of books for personal organization
 * and social discovery. Unlike collections (admin-managed access boundaries),
 * shelves are personal - each belongs to one user.
 */
@Entity(
    tableName = "shelves",
    indices = [Index("ownerId"), Index("syncState")],
)
data class ShelfEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String?,
    val ownerId: String,
    val ownerDisplayName: String,
    val ownerAvatarColor: String,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val coverPaths: List<String> = emptyList(),
    val syncState: SyncState = SyncState.SYNCED,
) {
    /**
     * Returns the display name formatted for the current user context.
     *
     * - Owner sees: "To Read"
     * - Others see: "Simon's To Read"
     */
    fun displayName(currentUserId: String): String = if (ownerId == currentUserId) name else "$ownerDisplayName's $name"
}
