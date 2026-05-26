package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.BookTagsTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.db.TagTable
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SearchServiceImplTest :
    FunSpec({

        test("search with blank query returns empty lists across all categories") {
            withInMemoryDatabase {
                val service = SearchServiceImpl(db = this)
                runTest {
                    val result = service.search(query = "  ", limit = 20) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                    result.data.contributors.shouldBeEmpty()
                    result.data.series.shouldBeEmpty()
                }
            }
        }

        test("search with no matches returns empty lists, not failure") {
            withInMemoryDatabase {
                val service = SearchServiceImpl(db = this)
                runTest {
                    val result = service.search(query = "xyznosuchterm", limit = 20) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                    result.data.contributors.shouldBeEmpty()
                    result.data.series.shouldBeEmpty()
                }
            }
        }

        test("search escapes FTS5 special characters without throwing") {
            withInMemoryDatabase {
                val service = SearchServiceImpl(db = this)
                runTest {
                    for (dangerous in listOf("abc\"def", "abc;DROP TABLE books", "abc*", "(test)", "abc:def")) {
                        service.search(query = dangerous, limit = 20).shouldBeInstanceOf<AppResult.Success<*>>()
                    }
                }
            }
        }

        test("search returns book hits when a matching book exists") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "b1", title = "The Way of Kings", libraryId = libId)
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Kings", limit = 20) as AppResult.Success<SearchResults>
                    result.data.books shouldHaveSize 1
                    result.data.books[0].title shouldBe "The Way of Kings"
                }
            }
        }

        test("search returns contributor hits when a matching contributor exists") {
            withInMemoryDatabase {
                val db = this
                seedContributor(db = db, contributorId = "c1", name = "Brandon Sanderson")
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Sanderson", limit = 20) as AppResult.Success<SearchResults>
                    result.data.contributors shouldHaveSize 1
                    result.data.contributors[0].name shouldBe "Brandon Sanderson"
                }
            }
        }

        test("search returns series hits when a matching series exists") {
            withInMemoryDatabase {
                val db = this
                seedSeries(db = db, seriesId = "s1", name = "Stormlight Archive")
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Stormlight", limit = 20) as AppResult.Success<SearchResults>
                    result.data.series shouldHaveSize 1
                    result.data.series[0].name shouldBe "Stormlight Archive"
                }
            }
        }

        test("search respects limit per category") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                repeat(10) { i -> seedBook(db = db, bookId = "bk$i", title = "Dragon Test Book $i", libraryId = libId) }
                repeat(10) { i -> seedContributor(db = db, contributorId = "co$i", name = "Dragon Author $i") }
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Dragon", limit = 3) as AppResult.Success<SearchResults>
                    result.data.books.size shouldBe 3
                    result.data.contributors.size shouldBe 3
                }
            }
        }

        test("search does not return soft-deleted books") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "del1", title = "Deleted Unique Phantasm", libraryId = libId, deleted = true)
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Phantasm", limit = 20) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                }
            }
        }

        test("book hit includes author names from book_contributors") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "b2", title = "Mistborn", libraryId = libId)
                seedContributor(db = db, contributorId = "ca1", name = "Brandon Sanderson")
                seedBookContributor(db = db, bookId = "b2", contributorId = "ca1", role = "author", ordinal = 0)
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Mistborn", limit = 20) as AppResult.Success<SearchResults>
                    result.data.books shouldHaveSize 1
                    result.data.books[0].authorNames shouldBe listOf("Brandon Sanderson")
                }
            }
        }

        // ── tag search tests ──────────────────────────────────────────────────

        test("search returns tag hits when a matching tag exists") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "b1", title = "Project Hail Mary", libraryId = libId)
                seedTag(db = db, tagId = "t1", name = "Sci-Fi", slug = "sci-fi")
                seedBookTag(db = db, bookId = "b1", tagId = "t1")
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Sci", limit = 20) as AppResult.Success<SearchResults>
                    result.data.tags shouldHaveSize 1
                    result.data.tags[0].name shouldBe "Sci-Fi"
                    result.data.tags[0].slug shouldBe "sci-fi"
                    result.data.tags[0].bookCount shouldBe 1L
                }
            }
        }

        test("search returns empty tag list when no matching tag exists") {
            withInMemoryDatabase {
                val db = this
                seedTag(db = db, tagId = "t1", name = "Fantasy", slug = "fantasy")
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "xyznosuchterm", limit = 20) as AppResult.Success<SearchResults>
                    result.data.tags.shouldBeEmpty()
                }
            }
        }

        test("search excludes soft-deleted tags") {
            withInMemoryDatabase {
                val db = this
                seedTag(db = db, tagId = "t1", name = "Mystery", slug = "mystery", deleted = true)
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Mystery", limit = 20) as AppResult.Success<SearchResults>
                    result.data.tags.shouldBeEmpty()
                }
            }
        }

        test("tag with no books returns bookCount of zero") {
            withInMemoryDatabase {
                val db = this
                seedTag(db = db, tagId = "t1", name = "Non-Fiction", slug = "non-fiction")
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Non", limit = 20) as AppResult.Success<SearchResults>
                    result.data.tags shouldHaveSize 1
                    result.data.tags[0].bookCount shouldBe 0L
                }
            }
        }

        test("bookCount excludes soft-deleted book_tags junctions") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "b1", title = "Book One", libraryId = libId)
                seedBook(db = db, bookId = "b2", title = "Book Two", libraryId = libId)
                seedTag(db = db, tagId = "t1", name = "Classics", slug = "classics")
                seedBookTag(db = db, bookId = "b1", tagId = "t1")
                seedBookTag(db = db, bookId = "b2", tagId = "t1", deleted = true)
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Classics", limit = 20) as AppResult.Success<SearchResults>
                    result.data.tags shouldHaveSize 1
                    // Only the live junction row counts.
                    result.data.tags[0].bookCount shouldBe 1L
                }
            }
        }

        test("search returns all four arrays in one call") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "bx", title = "Dragon Fire", libraryId = libId)
                seedContributor(db = db, contributorId = "cx", name = "Dragon Author")
                seedSeries(db = db, seriesId = "sx", name = "Dragon Chronicles")
                seedTag(db = db, tagId = "tx", name = "Dragon Age", slug = "dragon-age")
                val service = SearchServiceImpl(db = db)
                runTest {
                    val result = service.search(query = "Dragon", limit = 20) as AppResult.Success<SearchResults>
                    result.data.books shouldHaveSize 1
                    result.data.contributors shouldHaveSize 1
                    result.data.series shouldHaveSize 1
                    result.data.tags shouldHaveSize 1
                }
            }
        }
    })

// ── test data helpers ──────────────────────────────────────────────────────────

private fun seedLibrary(
    db: org.jetbrains.exposed.v1.jdbc.Database,
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
    db: org.jetbrains.exposed.v1.jdbc.Database,
    bookId: String,
    title: String,
    libraryId: String,
    deleted: Boolean = false,
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
            it[BookTable.totalDuration] = 3_600_000L
            it[BookTable.rootRelPath] = "$bookId/book.mp3"
            it[BookTable.scannedAt] = now
            it[BookTable.revision] = 1L
            it[BookTable.createdAt] = now
            it[BookTable.updatedAt] = now
            it[BookTable.deletedAt] = if (deleted) now else null
        }
        // Allocate the FTS rowid mapping.
        BookSearchMapTable.insert {
            it[BookSearchMapTable.bookId] = bookId
            it[BookSearchMapTable.rowid] = rowid.toInt()
        }
        // Write the FTS row manually (book_search is contentless_delete=1, no triggers).
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
    db: org.jetbrains.exposed.v1.jdbc.Database,
    contributorId: String,
    name: String,
    sortName: String? = null,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        ContributorTable.insert {
            it[ContributorTable.id] = contributorId
            it[ContributorTable.normalizedName] = name.lowercase()
            it[ContributorTable.name] = name
            it[ContributorTable.sortName] = sortName
            it[ContributorTable.revision] = 1L
            it[ContributorTable.createdAt] = now
            it[ContributorTable.updatedAt] = now
        }
        // V19 triggers auto-populate contributor_search on INSERT — no manual FTS write needed.
    }
}

private fun seedSeries(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    seriesId: String,
    name: String,
    sortName: String? = null,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        BookSeriesTable.insert {
            it[BookSeriesTable.id] = seriesId
            it[BookSeriesTable.normalizedName] = name.lowercase()
            it[BookSeriesTable.name] = name
            it[BookSeriesTable.sortName] = sortName
            it[BookSeriesTable.revision] = 1L
            it[BookSeriesTable.createdAt] = now
            it[BookSeriesTable.updatedAt] = now
        }
        // V19 triggers auto-populate series_search on INSERT — no manual FTS write needed.
    }
}

private fun seedBookContributor(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    bookId: String,
    contributorId: String,
    role: String,
    ordinal: Int,
) {
    transaction(db) {
        BookContributorTable.insert {
            it[BookContributorTable.bookId] = bookId
            it[BookContributorTable.contributorId] = contributorId
            it[BookContributorTable.role] = role
            it[BookContributorTable.creditedAs] = null
            it[BookContributorTable.ordinal] = ordinal
        }
    }
}

/**
 * Seeds a tag row. The [TagTable]'s `tags_ai` trigger automatically populates
 * [tag_search] on INSERT, so no manual FTS write is needed.
 *
 * Soft-deleted tags are excluded from search via `t.deleted_at IS NULL` in the
 * [SearchServiceImpl.searchTags] query.
 */
private fun seedTag(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    tagId: String,
    name: String,
    slug: String,
    deleted: Boolean = false,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        TagTable.insert {
            it[TagTable.id] = tagId
            it[TagTable.name] = name
            it[TagTable.slug] = slug
            it[TagTable.createdAt] = now
            it[TagTable.updatedAt] = now
            it[TagTable.revision] = 1L
            it[TagTable.deletedAt] = if (deleted) now else null
        }
    }
}

/**
 * Seeds a book_tags junction row.
 *
 * [deleted] = true simulates a soft-deleted junction that [SearchServiceImpl.searchTags]
 * excludes from the [bookCount] sub-query.
 */
private fun seedBookTag(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    bookId: String,
    tagId: String,
    deleted: Boolean = false,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        BookTagsTable.insert {
            it[BookTagsTable.id] = "$bookId:$tagId"
            it[BookTagsTable.bookId] = bookId
            it[BookTagsTable.tagId] = tagId
            it[BookTagsTable.createdAt] = now
            it[BookTagsTable.updatedAt] = now
            it[BookTagsTable.revision] = 1L
            it[BookTagsTable.deletedAt] = if (deleted) now else null
        }
    }
}
