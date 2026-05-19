package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-session row storing the current user's own reading history for a book.
 *
 * Replaces the aggregated self-row pattern (synthetic `id = "self-$bookId-$userId"`)
 * that lived in the old `reading_sessions` table. Each server-side reading session
 * lands as its own row, keyed by the server's session UUID. `BookReadersResponse.yourSessions`
 * from `/api/v1/books/{id}/readers` is the authoritative source.
 *
 * No denormalized user profile — the row is for the current user; `UserRepository`
 * owns their identity.
 *
 * Cascades on book deletion.
 *
 * @property id Server-issued session UUID. Matches `SessionSummary.id`.
 * @property bookId FK to the book this session belongs to.
 * @property userId Current user's ID at the time of the sync.
 * @property startedAt Session start (epoch ms).
 * @property finishedAt Session finish (epoch ms), null if still in progress.
 * @property isCompleted True if this session completed the book.
 * @property listenTimeMs Server-reported listen time accumulated within this session.
 * @property updatedAt Row-level cache freshness (epoch ms).
 */
@Entity(
    tableName = "user_reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["userId"]),
    ],
)
data class UserReadingSessionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val userId: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val isCompleted: Boolean,
    val listenTimeMs: Long,
    val updatedAt: Long,
)
