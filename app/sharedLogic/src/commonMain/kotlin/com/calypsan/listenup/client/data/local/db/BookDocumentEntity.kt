package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.calypsan.listenup.core.BookId

/**
 * Per-book supplementary document row (PDF / ebook that ships alongside the audiobook).
 *
 * The client mirror of the server's `book_documents` table. Like [AudioFileEntity], this
 * makes ordering structural (PK includes `index`) and gives readers a typed Room query
 * instead of a JSON blob. Documents are synced with their parent book and cascade on its
 * deletion. The bytes themselves are NOT stored here — they're fetched on demand and cached
 * on disk (see the document cache); this row is the metadata + the `id` the serve route keys on.
 *
 * @property bookId FK to the book.
 * @property index 0-based position within the book's document list.
 * @property id Server document UUID — the `{docId}` of `GET /books/{bookId}/documents/{docId}`.
 * @property filename Book-root-relative path (e.g. `"map.pdf"` or `"extras/map.pdf"`); the UI shows its basename.
 * @property format File extension in lowercase (e.g. `"pdf"`, `"epub"`).
 * @property size File size in bytes.
 * @property hash SHA-256 hex digest of the document's bytes — the serve-route ETag and the cache key.
 */
@Entity(
    tableName = "book_documents",
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
internal data class BookDocumentEntity(
    val bookId: BookId,
    val index: Int,
    val id: String,
    val filename: String,
    val format: String,
    val size: Long,
    val hash: String,
)
