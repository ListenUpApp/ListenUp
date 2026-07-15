package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest

class SearchReindexServiceTest :
    FunSpec({
        test("reindexAll rebuilds book_search so a wiped index recovers") {
            withSqlDatabase {
                seedLibrary()
                seedBook("b1", "The Way of Kings")
                runTest {
                    // Simulate index drift: wipe the contentless book_search FTS index.
                    driver.execute(null, "DELETE FROM book_search", 0)
                    val before =
                        SearchServiceImpl(db = sql, driver = driver)
                            .search(SearchQuery(text = "Kings")) as AppResult.Success<SearchResults>
                    before.data.books shouldHaveSize 0

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver)

                    SearchReindexService(db = sql, driver = driver, reindexer = reindexer).reindexAll()

                    val after =
                        SearchServiceImpl(db = sql, driver = driver)
                            .search(SearchQuery(text = "Kings")) as AppResult.Success<SearchResults>
                    after.data.books shouldHaveSize 1
                }
            }
        }

        test("reindexAll rebuilds the contentless contributor_search so a wiped index recovers") {
            withSqlDatabase {
                seedContributor("c1", "Brandon Sanderson")
                runTest {
                    // Simulate index drift: wipe the contentless contributor_search FTS index.
                    driver.execute(null, "DELETE FROM contributor_search", 0)
                    val before =
                        SearchServiceImpl(db = sql, driver = driver)
                            .search(SearchQuery(text = "Sanderson")) as AppResult.Success<SearchResults>
                    before.data.contributors shouldHaveSize 0

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver)

                    SearchReindexService(db = sql, driver = driver, reindexer = reindexer).reindexAll()

                    val after =
                        SearchServiceImpl(db = sql, driver = driver)
                            .search(SearchQuery(text = "Sanderson")) as AppResult.Success<SearchResults>
                    after.data.contributors shouldHaveSize 1
                }
            }
        }
    })

// ── test data helpers ────────────────────────────────────────────────────────

private fun SqlTestDatabases.seedLibrary(
    libraryId: String = "lib1",
    path: String = "/tmp/testlibrary",
    folderId: String = "folder1",
) {
    val now = System.currentTimeMillis()
    sql.transaction {
        sql.librariesQueries.insert(
            id = libraryId,
            name = "Test Library",
            metadata_precedence = "embedded",
            access_mode = "shared",
            created_by_user_id = null,
            created_at = now,
            revision = 0L,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
        )
        sql.libraryFoldersQueries.insert(
            id = folderId,
            library_id = libraryId,
            root_path = path,
            created_at = now,
            revision = 0L,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
        )
    }
}

private fun SqlTestDatabases.seedBook(
    bookId: String,
    title: String,
    libraryId: String = "lib1",
    folderId: String = "folder1",
) {
    val now = System.currentTimeMillis()
    val rowid = (bookId.hashCode().toLong().let { if (it < 0) -it else it } % 999_999L) + 1L
    sql.transaction {
        sql.booksQueries.insert(
            id = bookId,
            library_id = libraryId,
            folder_id = folderId,
            title = title,
            sort_title = title,
            subtitle = null,
            description = null,
            publish_year = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = 0L,
            explicit = 0L,
            has_scan_warning = 0L,
            total_duration = 3_600_000L,
            cover_source = null,
            cover_path = null,
            cover_hash = null,
            field_provenance = "{}",
            root_rel_path = "$bookId/book.mp3",
            inode = null,
            scanned_at = now,
            revision = 1L,
            created_at = now,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
        )
        // Allocate the FTS rowid mapping; reindexBook resolves the row via book_search_map.
        sql.bookSearchQueries.insertMap(book_id = bookId, rowid = rowid)
        sql.bookSearchQueries.deleteFtsRow(rowid = rowid)
        sql.bookSearchQueries.insertFtsRow(
            rowid = rowid,
            title = title,
            subtitle = "",
            description = "",
            contributor_names = "",
            series_names = "",
            tags = "",
            genres = "",
        )
    }
}

private fun SqlTestDatabases.seedContributor(
    contributorId: String,
    name: String,
) {
    val now = System.currentTimeMillis()
    sql.transaction {
        sql.contributorsQueries.insert(
            id = contributorId,
            normalized_name = name.lowercase(),
            name = name,
            sort_name = null,
            revision = 1L,
            created_at = now,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
            asin = null,
            description = null,
            image_path = null,
            birth_date = null,
            death_date = null,
            website = null,
        )
        // V19/V22 triggers auto-populate contributor_search on INSERT via migration DDL triggers.
    }
}
