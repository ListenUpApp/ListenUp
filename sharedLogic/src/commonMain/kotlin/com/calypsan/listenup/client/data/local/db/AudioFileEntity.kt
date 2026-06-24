package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.calypsan.listenup.core.BookId

/**
 * Per-book audio file row.
 *
 * Replaces the legacy [BookEntity.audioFilesJson] JSON blob, which serialized a
 * `List<AudioFileResponse>` into a single TEXT column and forced JSON parsing on
 * the playback hot path. This junction makes ordering structural (PK includes
 * `index`) and gives all readers a typed Room query.
 *
 * Cascades on book deletion.
 *
 * @property bookId FK to the book.
 * @property index 0-based position within the book. Determines playback order.
 * @property id Server audio file UUID. Stored as a reference field (not PK) so
 *   re-encoded files with new server IDs update in place via `(bookId, index)`.
 * @property filename Original filename (e.g., "chapter01.m4b").
 * @property format Container format (e.g., "m4b", "mp3", "opus").
 * @property codec Audio codec (e.g., "aac", "mp3"). May be empty for older server versions.
 * @property duration File duration in milliseconds.
 * @property size File size in bytes.
 */
@Entity(
    tableName = "audio_files",
    primaryKeys = ["bookId", "index"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
internal data class AudioFileEntity(
    val bookId: BookId,
    val index: Int,
    val id: String,
    val filename: String,
    val format: String,
    val codec: String,
    val duration: Long,
    val size: Long,
    val codecProfile: String? = null,
    val spatial: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
)
