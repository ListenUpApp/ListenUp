package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SearchReindexServiceTest :
    FunSpec({
        test("reindexAll rebuilds book_search so a wiped index recovers") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                seedBook(db, "b1", "The Way of Kings")
                runTest {
                    // Simulate index drift: wipe the contentless book_search FTS index.
                    transaction(db) { TransactionManager.current().exec("DELETE FROM book_search") }
                    val before =
                        SearchServiceImpl(db).search(SearchQuery(text = "Kings")) as AppResult.Success<SearchResults>
                    before.data.books shouldHaveSize 0

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
                    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)

                    SearchReindexService(db, reindexer).reindexAll()

                    val after =
                        SearchServiceImpl(db).search(SearchQuery(text = "Kings")) as AppResult.Success<SearchResults>
                    after.data.books shouldHaveSize 1
                }
            }
        }

        test("reindexAll rebuilds the contentless contributor_search so a wiped index recovers") {
            withInMemoryDatabase {
                val db = this
                seedContributor(db, "c1", "Brandon Sanderson")
                runTest {
                    // Simulate index drift: wipe the contentless contributor_search FTS index.
                    transaction(db) { TransactionManager.current().exec("DELETE FROM contributor_search") }
                    val before =
                        SearchServiceImpl(db)
                            .search(SearchQuery(text = "Sanderson")) as AppResult.Success<SearchResults>
                    before.data.contributors shouldHaveSize 0

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
                    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)

                    SearchReindexService(db, reindexer).reindexAll()

                    val after =
                        SearchServiceImpl(db)
                            .search(SearchQuery(text = "Sanderson")) as AppResult.Success<SearchResults>
                    after.data.contributors shouldHaveSize 1
                }
            }
        }
    })

// ── test data helpers (minimal copies of SearchServiceImplTest's seeders) ────────

private fun seedLibrary(
    db: Database,
    libraryId: String = "lib1",
    path: String = "/tmp/testlibrary",
    folderId: String = "folder1",
): String {
    val now = System.currentTimeMillis()
    transaction(db) {
        LibraryTable.insert {
            it[LibraryTable.id] = libraryId
            it[LibraryTable.name] = "Test Library"
            it[LibraryTable.metadataPrecedence] = "embedded"
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        LibraryFolderTable.insert {
            it[LibraryFolderTable.id] = folderId
            it[LibraryFolderTable.libraryId] = libraryId
            it[LibraryFolderTable.rootPath] = path
            it[LibraryFolderTable.createdAt] = now
            it[LibraryFolderTable.updatedAt] = now
            it[LibraryFolderTable.revision] = 0L
            it[LibraryFolderTable.deletedAt] = null
        }
    }
    return libraryId
}

private fun seedBook(
    db: Database,
    bookId: String,
    title: String,
    libraryId: String = "lib1",
    folderId: String = "folder1",
) {
    val now = System.currentTimeMillis()
    val rowid = (bookId.hashCode().toLong().let { if (it < 0) -it else it } % 999_999L) + 1L
    transaction(db) {
        BookTable.insert {
            it[BookTable.id] = bookId
            it[BookTable.libraryId] = libraryId
            it[BookTable.folderId] = folderId
            it[BookTable.title] = title
            it[BookTable.sortTitle] = title
            it[BookTable.totalDuration] = 3_600_000L
            it[BookTable.rootRelPath] = "$bookId/book.mp3"
            it[BookTable.scannedAt] = now
            it[BookTable.revision] = 1L
            it[BookTable.createdAt] = now
            it[BookTable.updatedAt] = now
            it[BookTable.deletedAt] = null
        }
        // Allocate the FTS rowid mapping; reindexBook resolves the row via book_search_map.
        BookSearchMapTable.insert {
            it[BookSearchMapTable.bookId] = bookId
            it[BookSearchMapTable.rowid] = rowid.toInt()
        }
        val tx = TransactionManager.current()
        tx.exec("DELETE FROM book_search WHERE rowid = $rowid")
        val cols = "rowid, title, subtitle, description, contributor_names, series_names"
        tx.exec(
            stmt = "INSERT INTO book_search($cols) VALUES($rowid, ?, '', '', '', '')",
            args = listOf(TextColumnType() to title),
        )
    }
}

private fun seedContributor(
    db: Database,
    contributorId: String,
    name: String,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        ContributorTable.insert {
            it[ContributorTable.id] = contributorId
            it[ContributorTable.normalizedName] = name.lowercase()
            it[ContributorTable.name] = name
            it[ContributorTable.sortName] = null
            it[ContributorTable.revision] = 1L
            it[ContributorTable.createdAt] = now
            it[ContributorTable.updatedAt] = now
        }
        // V19/V22 triggers auto-populate contributor_search on INSERT.
    }
}
