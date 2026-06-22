package com.calypsan.listenup.client.domain.model

/**
 * A supplementary document (PDF, ebook, etc.) that ships alongside an audiobook.
 *
 * This is the entity-free domain representation of [BookDocumentEntity]. The bytes themselves
 * are not held here — they are fetched on demand and cached on disk via [DocumentRepository].
 *
 * @property id Server document UUID — the `{docId}` in `GET /api/v1/books/{bookId}/documents/{docId}`.
 * @property index 0-based position within the book's document list; used for stable ordering.
 * @property filename Book-root-relative path (e.g. `"map.pdf"` or `"extras/map.pdf"`); the UI
 *   displays the basename.
 * @property format File extension in lowercase (e.g. `"pdf"`, `"epub"`).
 * @property size File size in bytes.
 * @property hash SHA-256 hex digest of the document's bytes — the ETag the server route serves
 *   and the cache-validity key.
 */
data class BookDocument(
    val id: String,
    val index: Int,
    val filename: String,
    val format: String,
    val size: Long,
    val hash: String,
)
