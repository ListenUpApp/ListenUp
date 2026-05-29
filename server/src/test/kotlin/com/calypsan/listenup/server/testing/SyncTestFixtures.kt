package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
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

/**
 * Seeds a minimal user row with the given [userId] into the receiver database.
 *
 * Used in tests that insert `collection_shares` rows — the junction table's FK
 * `shared_with_user_id REFERENCES users(id)` requires the parent row to exist when FK
 * enforcement is enabled.
 */
fun Database.seedTestUser(userId: String) {
    transaction(this) {
        UserEntity.new(userId) {
            email = "$userId@example.com"
            emailNormalized = "$userId@example.com"
            passwordHash = "phc"
            role = UserRoleColumn.MEMBER
            displayName = userId
            status = UserStatusColumn.ACTIVE
            createdAt = 1L
            updatedAt = 1L
        }
    }
}

/**
 * Canonical fixture builder for [BookSyncPayload] test instances.
 *
 * All fields have sensible defaults so a minimal call works for the common case.
 * Override specific fields by name as the test requires.
 *
 * Replaces two divergent inline fixtures that existed in `BookServiceImplTest`
 * (`bookFixture`) and `BookRepositoryUpsertTest` (`bookPayloadFixture`).
 *
 * @param id the book id — required (no universal default).
 * @param title the book title — required (no universal default).
 * @param rootRelPath the relative path; defaults to `"books/$id"`.
 * @param contributors pre-resolved [BookContributorPayload] list; default empty.
 * @param series pre-resolved [BookSeriesPayload] list; default empty.
 * @param chapters [BookChapterPayload] list; default empty.
 * @param audioFiles [BookAudioFilePayload] list; default empty. [BookSyncPayload.totalDuration]
 *   is derived as the sum of individual file durations.
 * @param cover optional [CoverPayload]; default null.
 */
fun bookPayloadFixture(
    id: String,
    title: String,
    rootRelPath: String = "books/$id",
    contributors: List<BookContributorPayload> = emptyList(),
    series: List<BookSeriesPayload> = emptyList(),
    chapters: List<BookChapterPayload> = emptyList(),
    audioFiles: List<BookAudioFilePayload> = emptyList(),
    cover: CoverPayload? = null,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = null,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = audioFiles.sumOf { it.duration },
        cover = cover,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = contributors,
        series = series,
        audioFiles = audioFiles,
        chapters = chapters,
        // The substrate authors the persisted revision/timestamps; the wire payload
        // values here are placeholders for the test, ignored by writePayload.
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
