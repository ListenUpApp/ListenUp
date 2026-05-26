package com.calypsan.listenup.server.testing

import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

/**
 * Runs [block] with a freshly-migrated in-memory SQLite [Database] as the receiver.
 *
 * Uses a temp-file database (not `:memory:`) so that Flyway's schema-history
 * table and SQLite's single-connection constraint both work correctly. The file
 * is deleted on JVM exit.
 */
fun withInMemoryDatabase(block: Database.() -> Unit) {
    val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
    val db = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
    db.block()
}

/**
 * Seeds a test library row with id `"test-library"` and a test folder row with
 * id `"test-folder"` into the receiver database. Use this in tests that
 * call [com.calypsan.listenup.server.services.BookRepository.upsert] with
 * `libraryId = LibraryId("test-library")` and `folderId = FolderId("test-folder")`,
 * so the FK constraints in [LibraryTable] and [LibraryFolderTable] are satisfied.
 *
 * @param folderPath the [LibraryFolderTable.rootPath] value (default `/tmp/test-library`).
 */
fun Database.seedTestLibraryAndFolder(
    libraryId: String = "test-library",
    folderId: String = "test-folder",
    folderPath: String = "/tmp/test-library",
) {
    val now = System.currentTimeMillis()
    transaction(this) {
        LibraryTable.insert {
            it[LibraryTable.id] = libraryId
            it[LibraryTable.name] = "Test Library"
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        // Bootstrap (LibraryRegistry) may have already inserted a folder at this
        // path. Remove it so we can insert the test-controlled row with the
        // canonical id ("test-folder") that book fixtures reference via folderId.
        LibraryFolderTable.deleteWhere { LibraryFolderTable.rootPath eq folderPath }
        LibraryFolderTable.insert {
            it[LibraryFolderTable.id] = folderId
            it[LibraryFolderTable.libraryId] = libraryId
            it[LibraryFolderTable.rootPath] = folderPath
            it[LibraryFolderTable.createdAt] = now
            it[LibraryFolderTable.updatedAt] = now
            it[LibraryFolderTable.revision] = 0L
            it[LibraryFolderTable.deletedAt] = null
        }
    }
}

/**
 * Seeds a minimal book row with the given [bookId] into the receiver database.
 *
 * Used in tests that insert `book_tags` rows — the junction table's FK
 * `book_id REFERENCES books(id)` requires the parent row to exist when FK
 * enforcement is enabled.
 *
 * @param libraryId the [BookTable.libraryId] value (default `"test-library"`).
 * @param folderId the [BookTable.folderId] value (default `"test-folder"`).
 */
fun Database.seedTestBook(
    bookId: String,
    libraryId: String = "test-library",
    folderId: String = "test-folder",
) {
    val now = System.currentTimeMillis()
    transaction(this) {
        BookTable.insert {
            it[BookTable.id] = bookId
            it[BookTable.libraryId] = libraryId
            it[BookTable.folderId] = folderId
            it[BookTable.title] = "Test Book $bookId"
            it[BookTable.totalDuration] = 0L
            it[BookTable.rootRelPath] = "$bookId/book.m4b"
            it[BookTable.scannedAt] = now
            it[BookTable.revision] = 1L
            it[BookTable.createdAt] = now
            it[BookTable.updatedAt] = now
        }
    }
}
