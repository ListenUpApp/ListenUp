package com.calypsan.listenup.server.testing

import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import org.jetbrains.exposed.v1.jdbc.Database
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
