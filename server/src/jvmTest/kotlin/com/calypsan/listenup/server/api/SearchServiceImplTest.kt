package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SearchFilters
import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SearchSort
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.BookTagsTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.db.TagTable
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
                val service = SearchServiceImpl(db = this.asSqlDatabase(), driver = this.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "  ", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                    result.data.contributors.shouldBeEmpty()
                    result.data.series.shouldBeEmpty()
                }
            }
        }

        test("search with no matches returns empty lists, not failure") {
            withInMemoryDatabase {
                val service = SearchServiceImpl(db = this.asSqlDatabase(), driver = this.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "xyznosuchterm", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                    result.data.contributors.shouldBeEmpty()
                    result.data.series.shouldBeEmpty()
                }
            }
        }

        test("search escapes FTS5 special characters without throwing") {
            withInMemoryDatabase {
                val service = SearchServiceImpl(db = this.asSqlDatabase(), driver = this.asSqlDriver())
                runTest {
                    for (dangerous in listOf("abc\"def", "abc;DROP TABLE books", "abc*", "(test)", "abc:def")) {
                        service.search(SearchQuery(text = dangerous, limit = 20)).shouldBeInstanceOf<AppResult.Success<*>>()
                    }
                }
            }
        }

        test("search returns book hits when a matching book exists") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "b1", title = "The Way of Kings", libraryId = libId)
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Kings", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books shouldHaveSize 1
                    result.data.books[0].title shouldBe "The Way of Kings"
                }
            }
        }

        test("search returns contributor hits when a matching contributor exists") {
            withInMemoryDatabase {
                val db = this
                seedContributor(db = db, contributorId = "c1", name = "Brandon Sanderson")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Sanderson", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.contributors shouldHaveSize 1
                    result.data.contributors[0].name shouldBe "Brandon Sanderson"
                    result.data.contributors[0].highlight shouldNotBe null
                }
            }
        }

        test("search returns series hits when a matching series exists") {
            withInMemoryDatabase {
                val db = this
                seedSeries(db = db, seriesId = "s1", name = "Stormlight Archive")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Stormlight", limit = 20)) as AppResult.Success<SearchResults>
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
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Dragon", limit = 3)) as AppResult.Success<SearchResults>
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
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Phantasm", limit = 20)) as AppResult.Success<SearchResults>
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
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Mistborn", limit = 20)) as AppResult.Success<SearchResults>
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
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Sci", limit = 20)) as AppResult.Success<SearchResults>
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
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "xyznosuchterm", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.tags.shouldBeEmpty()
                }
            }
        }

        test("search excludes soft-deleted tags") {
            withInMemoryDatabase {
                val db = this
                seedTag(db = db, tagId = "t1", name = "Mystery", slug = "mystery", deleted = true)
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Mystery", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.tags.shouldBeEmpty()
                }
            }
        }

        test("tag with no books returns bookCount of zero") {
            withInMemoryDatabase {
                val db = this
                seedTag(db = db, tagId = "t1", name = "Non-Fiction", slug = "non-fiction")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Non", limit = 20)) as AppResult.Success<SearchResults>
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
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Classics", limit = 20)) as AppResult.Success<SearchResults>
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
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val result = service.search(SearchQuery(text = "Dragon", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books shouldHaveSize 1
                    result.data.contributors shouldHaveSize 1
                    result.data.series shouldHaveSize 1
                    result.data.tags shouldHaveSize 1
                }
            }
        }

        test("sort=Title orders books alphabetically by sort title") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "b1", "Dragon Zephyr", lib)
                seedBook(db, "b2", "Dragon Alpha", lib)
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", sort = SearchSort.Title),
                        ) as AppResult.Success<SearchResults>
                    r.data.books.map { it.title } shouldBe listOf("Dragon Alpha", "Dragon Zephyr")
                }
            }
        }

        test("sort=Duration orders books shortest first") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "long", "Dragon L", lib, durationSeconds = 36_000)
                seedBook(db, "short", "Dragon S", lib, durationSeconds = 3_600)
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", sort = SearchSort.Duration),
                        ) as AppResult.Success<SearchResults>
                    r.data.books.map { it.id.value } shouldBe listOf("short", "long")
                }
            }
        }

        test("non-relevance sort collapses results to books only") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "b1", title = "Dragon Quest", libraryId = libId)
                seedContributor(db = db, contributorId = "c1", name = "Dragon Author")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", sort = SearchSort.Title),
                        ) as AppResult.Success<SearchResults>
                    r.data.books shouldHaveSize 1
                    r.data.contributors.shouldBeEmpty()
                    r.data.series.shouldBeEmpty()
                    r.data.tags.shouldBeEmpty()
                }
            }
        }

        test("active filter collapses results to books only") {
            withInMemoryDatabase {
                val db = this
                val libId = seedLibrary(db)
                seedBook(db = db, bookId = "b1", title = "Dragon Quest", libraryId = libId)
                seedContributor(db = db, contributorId = "c1", name = "Dragon Author")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", filters = SearchFilters(genreSlugs = listOf("anything"))),
                        ) as AppResult.Success<SearchResults>
                    // Asserts the books-only collapse only (non-book lists empty), independent of filter contents.
                    r.data.contributors.shouldBeEmpty()
                    r.data.series.shouldBeEmpty()
                    r.data.tags.shouldBeEmpty()
                }
            }
        }

        test("genre slug filter narrows books to that genre") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "b1", "Dragon One", lib)
                seedBook(db, "b2", "Dragon Two", lib)
                seedGenre(db, "g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                linkBookGenre(db, "b1", "g-fan")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", filters = SearchFilters(genreSlugs = listOf("fantasy"))),
                        ) as AppResult.Success<SearchResults>
                    r.data.books.map { it.id.value } shouldBe listOf("b1")
                }
            }
        }

        test("genre path filter matches the whole subtree but not sibling-prefixed paths") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "b1", "Dragon Epic", lib)
                seedBook(db, "b2", "Dragon Sci", lib)
                seedBook(db, "b3", "Dragon Class", lib)
                seedGenre(db, "g-epic", "epic", "/fiction/fantasy/epic")
                seedGenre(db, "g-sci", "scifi", "/fiction/scifi")
                seedGenre(db, "g-fanclassics", "fanclassics", "/fiction/fantasy-classics")
                linkBookGenre(db, "b1", "g-epic")
                linkBookGenre(db, "b2", "g-sci")
                linkBookGenre(db, "b3", "g-fanclassics")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", filters = SearchFilters(genrePath = "/fiction/fantasy")),
                        ) as AppResult.Success<SearchResults>
                    r.data.books.map { it.id.value } shouldBe listOf("b1")
                }
            }
        }

        test("duration filter keeps only books within the range") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "short", "Dragon Short", lib, durationSeconds = 3_600) // 1h
                seedBook(db, "long", "Dragon Long", lib, durationSeconds = 36_000) // 10h
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", filters = SearchFilters(durationMaxSeconds = 7_200)),
                        ) as AppResult.Success<SearchResults>
                    r.data.books.map { it.id.value } shouldBe listOf("short")
                }
            }
        }

        test("year filter keeps only books within the range") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "old", "Dragon Old", lib, publishYear = 1999)
                seedBook(db, "new", "Dragon New", lib, publishYear = 2020)
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", filters = SearchFilters(yearMin = 2010)),
                        ) as AppResult.Success<SearchResults>
                    r.data.books.map { it.id.value } shouldBe listOf("new")
                }
            }
        }

        test("combined genre + year filters narrow correctly") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "match", "Dragon Match", lib, publishYear = 2020)
                seedBook(db, "wrongYear", "Dragon WrongYear", lib, publishYear = 1990)
                seedBook(db, "noGenre", "Dragon NoGenre", lib, publishYear = 2020)
                seedGenre(db, "g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                linkBookGenre(db, "match", "g-fan")
                linkBookGenre(db, "wrongYear", "g-fan")
                // "noGenre" deliberately has no genre link
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", filters = SearchFilters(genreSlugs = listOf("fantasy"), yearMin = 2010)),
                        ) as AppResult.Success<SearchResults>
                    r.data.books.map { it.id.value } shouldBe listOf("match")
                }
            }
        }

        test("facets count genres and authors over the matched book set") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "b1", "Dragon One", lib)
                seedBook(db, "b2", "Dragon Two", lib)
                seedGenre(db, "g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                linkBookGenre(db, "b1", "g-fan")
                linkBookGenre(db, "b2", "g-fan")
                seedContributor(db, "c1", "Some Author")
                seedBookContributor(db = db, bookId = "b1", contributorId = "c1", role = "author", ordinal = 0)
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r = service.search(SearchQuery(text = "Dragon")) as AppResult.Success<SearchResults>
                    r.data.facets.genres
                        .first { it.key == "fantasy" }
                        .count shouldBe 2
                    r.data.facets.genres
                        .first { it.key == "fantasy" }
                        .label shouldBe "Fantasy"
                    r.data.facets.authors
                        .first { it.key == "c1" }
                        .count shouldBe 1
                    r.data.facets.types.books shouldBe 2
                }
            }
        }

        test("book hits carry a title highlight for the matched term") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "b1", "The Way of Kings", lib)
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r = service.search(SearchQuery(text = "Kings")) as AppResult.Success<SearchResults>
                    r.data.books[0].highlight shouldBe "The Way of ${HL_START}Kings$HL_END"
                }
            }
        }

        test("facets reflect the active filter, not the unfiltered match set") {
            withInMemoryDatabase {
                val db = this
                val lib = seedLibrary(db)
                seedBook(db, "f1", "Dragon F1", lib)
                seedBook(db, "f2", "Dragon F2", lib)
                seedBook(db, "s1", "Dragon S1", lib)
                seedGenre(db, "g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                seedGenre(db, "g-sci", "scifi", "/fiction/scifi", "Sci-Fi")
                linkBookGenre(db, "f1", "g-fan")
                linkBookGenre(db, "f2", "g-fan")
                linkBookGenre(db, "s1", "g-sci")
                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
                runTest {
                    val r =
                        service.search(
                            SearchQuery(text = "Dragon", filters = SearchFilters(genreSlugs = listOf("fantasy"))),
                        ) as AppResult.Success<SearchResults>
                    r.data.facets.types.books shouldBe 2
                    r.data.facets.genres
                        .first { it.key == "fantasy" }
                        .count shouldBe 2
                    r.data.facets.genres
                        .none { it.key == "scifi" } shouldBe true
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
    durationSeconds: Long = 3_600L,
    publishYear: Int? = null,
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
            // total_duration is stored in milliseconds; the contract field (durationSeconds) is in seconds.
            it[BookTable.totalDuration] = durationSeconds * 1_000L
            it[BookTable.publishYear] = publishYear
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

private fun seedGenre(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    id: String,
    slug: String,
    path: String,
    name: String = slug,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        GenreTable.insert {
            it[GenreTable.id] = id
            it[GenreTable.name] = name
            it[GenreTable.slug] = slug
            it[GenreTable.path] = path
            it[GenreTable.revision] = 1L
            it[GenreTable.createdAt] = now
            it[GenreTable.updatedAt] = now
        }
    }
}

private fun linkBookGenre(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    bookId: String,
    genreId: String,
) {
    transaction(db) {
        BookGenreTable.insert {
            it[BookGenreTable.bookId] = bookId
            it[BookGenreTable.genreId] = genreId
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
