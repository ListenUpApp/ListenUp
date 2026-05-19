package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * The books domain table — Books-A's only syncable table.
 *
 * Inherits `revision`, `created_at`, `updated_at`, `deleted_at`, `client_op_id`
 * from [SyncableTable]. The natural key `(library_id, root_rel_path)` is the
 * primary identity surface for the scanner; `(library_id, inode)` is the
 * move-detection fallback so a rename-in-place doesn't surface as a new book.
 *
 * Child rows (contributors, series, chapters, audio files) live in their own
 * tables and join in on every aggregate read/write — see `BookRepository`.
 */
internal object BookTable : SyncableTable("books") {
    val id = varchar("id", 36)
    val libraryId = reference("library_id", LibraryTable.id)
    val title = varchar("title", 1024)
    val sortTitle = varchar("sort_title", 1024).nullable()
    val subtitle = varchar("subtitle", 1024).nullable()
    val description = text("description").nullable()
    val publishYear = integer("publish_year").nullable()
    val publisher = varchar("publisher", 512).nullable()
    val language = varchar("language", 16).nullable()
    val isbn = varchar("isbn", 32).nullable()
    val asin = varchar("asin", 32).nullable()
    val abridged = bool("abridged").default(false)
    val explicit = bool("explicit").default(false)
    val hasScanWarning = bool("has_scan_warning").default(false)
    val totalDuration = long("total_duration")
    val coverSource = varchar("cover_source", 32).nullable()
    val coverPath = varchar("cover_path", 1024).nullable()
    val coverHash = varchar("cover_hash", 64).nullable()
    val rootRelPath = varchar("root_rel_path", 1024)
    val inode = long("inode").nullable()
    val scannedAt = long("scanned_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_book_natural_key", libraryId, rootRelPath)
        index("idx_book_inode", false, libraryId, inode)
        index("idx_book_sort_title", false, libraryId, sortTitle)
    }
}
