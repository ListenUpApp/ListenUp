package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-`(bookId, userId)` summary of another reader's activity on a book.
 *
 * Server-sourced cache. Populated from `/api/v1/books/{id}/readers` (via
 * `SessionRepositoryImpl.refreshBookReaders`) and from `/api/v1/sync/reading-sessions`
 * (via `ReadingSessionPuller`). Used by the Book Detail "Readers" section.
 *
 * Denormalized with user-display fields (display name, avatar) so the Readers list
 * renders without joining a users table — consistent with the design's "cache with
 * display freshness" pattern.
 *
 * Cascades on book deletion.
 *
 * The composite PK prevents collisions between readers on the same book; the
 * current user never writes here (their sessions live in `user_reading_sessions`).
 */
@Entity(
    tableName = "reader_sessions_cache",
    primaryKeys = ["bookId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["userId"])],
)
data class ReaderSessionCacheEntity(
    val bookId: String,
    val userId: String,
    val userDisplayName: String,
    val userAvatarColor: String,
    val userAvatarType: String,
    val userAvatarValue: String?,
    val isCurrentlyReading: Boolean,
    val currentProgress: Double,
    val startedAt: Long,
    val finishedAt: Long?,
    val completionCount: Int,
    val updatedAt: Long,
)
