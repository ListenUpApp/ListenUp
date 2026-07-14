package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SearchFilters
import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SearchSort
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class SearchServiceImplTest :
    FunSpec({

        test("search with blank query returns empty lists across all categories") {
            withSqlDatabase {
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "  ", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                    result.data.contributors.shouldBeEmpty()
                    result.data.series.shouldBeEmpty()
                }
            }
        }

        test("search with no matches returns empty lists, not failure") {
            withSqlDatabase {
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "xyznosuchterm", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                    result.data.contributors.shouldBeEmpty()
                    result.data.series.shouldBeEmpty()
                }
            }
        }

        test("search escapes FTS5 special characters without throwing") {
            withSqlDatabase {
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    for (dangerous in listOf("abc\"def", "abc;DROP TABLE books", "abc*", "(test)", "abc:def")) {
                        service.search(SearchQuery(text = dangerous, limit = 20)).shouldBeInstanceOf<AppResult.Success<*>>()
                    }
                }
            }
        }

        test("search returns book hits when a matching book exists") {
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "b1", title = "The Way of Kings", libraryId = libId)
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Kings", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books shouldHaveSize 1
                    result.data.books[0].title shouldBe "The Way of Kings"
                }
            }
        }

        test("search returns contributor hits when a matching contributor exists") {
            withSqlDatabase {
                sql.seedContributor(contributorId = "c1", name = "Brandon Sanderson")
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Sanderson", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.contributors shouldHaveSize 1
                    result.data.contributors[0].name shouldBe "Brandon Sanderson"
                    result.data.contributors[0].highlight shouldNotBe null
                }
            }
        }

        test("search returns series hits when a matching series exists") {
            withSqlDatabase {
                sql.seedSeries(seriesId = "s1", name = "Stormlight Archive")
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Stormlight", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.series shouldHaveSize 1
                    result.data.series[0].name shouldBe "Stormlight Archive"
                }
            }
        }

        test("search respects limit per category") {
            withSqlDatabase {
                val libId = seedLibrary(sql)
                repeat(10) { i -> seedBook(bookId = "bk$i", title = "Dragon Test Book $i", libraryId = libId) }
                repeat(10) { i -> sql.seedContributor(contributorId = "co$i", name = "Dragon Author $i") }
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Dragon", limit = 3)) as AppResult.Success<SearchResults>
                    result.data.books.size shouldBe 3
                    result.data.contributors.size shouldBe 3
                }
            }
        }

        test("search does not return soft-deleted books") {
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "del1", title = "Deleted Unique Phantasm", libraryId = libId, deleted = true)
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Phantasm", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books.shouldBeEmpty()
                }
            }
        }

        test("book hit includes author names from book_contributors") {
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "b2", title = "Mistborn", libraryId = libId)
                sql.seedContributor(contributorId = "ca1", name = "Brandon Sanderson")
                sql.seedBookContributor(bookId = "b2", contributorId = "ca1", role = "author", ordinal = 0)
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Mistborn", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.books shouldHaveSize 1
                    result.data.books[0].authorNames shouldBe listOf("Brandon Sanderson")
                }
            }
        }

        // ── tag search tests ──────────────────────────────────────────────────

        test("search returns tag hits when a matching tag exists") {
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "b1", title = "Project Hail Mary", libraryId = libId)
                sql.seedTag(tagId = "t1", name = "Sci-Fi", slug = "sci-fi")
                sql.seedBookTag(bookId = "b1", tagId = "t1")
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                sql.seedTag(tagId = "t1", name = "Fantasy", slug = "fantasy")
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "xyznosuchterm", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.tags.shouldBeEmpty()
                }
            }
        }

        test("search excludes soft-deleted tags") {
            withSqlDatabase {
                sql.seedTag(tagId = "t1", name = "Mystery", slug = "mystery", deleted = true)
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Mystery", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.tags.shouldBeEmpty()
                }
            }
        }

        test("tag with no books returns bookCount of zero") {
            withSqlDatabase {
                sql.seedTag(tagId = "t1", name = "Non-Fiction", slug = "non-fiction")
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Non", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.tags shouldHaveSize 1
                    result.data.tags[0].bookCount shouldBe 0L
                }
            }
        }

        test("bookCount excludes soft-deleted book_tags junctions") {
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "b1", title = "Book One", libraryId = libId)
                seedBook(bookId = "b2", title = "Book Two", libraryId = libId)
                sql.seedTag(tagId = "t1", name = "Classics", slug = "classics")
                sql.seedBookTag(bookId = "b1", tagId = "t1")
                sql.seedBookTag(bookId = "b2", tagId = "t1", deleted = true)
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val result = service.search(SearchQuery(text = "Classics", limit = 20)) as AppResult.Success<SearchResults>
                    result.data.tags shouldHaveSize 1
                    // Only the live junction row counts.
                    result.data.tags[0].bookCount shouldBe 1L
                }
            }
        }

        test("search returns all four arrays in one call") {
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "bx", title = "Dragon Fire", libraryId = libId)
                sql.seedContributor(contributorId = "cx", name = "Dragon Author")
                sql.seedSeries(seriesId = "sx", name = "Dragon Chronicles")
                sql.seedTag(tagId = "tx", name = "Dragon Age", slug = "dragon-age")
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("b1", "Dragon Zephyr", lib)
                seedBook("b2", "Dragon Alpha", lib)
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("long", "Dragon L", lib, durationSeconds = 36_000)
                seedBook("short", "Dragon S", lib, durationSeconds = 3_600)
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "b1", title = "Dragon Quest", libraryId = libId)
                sql.seedContributor(contributorId = "c1", name = "Dragon Author")
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val libId = seedLibrary(sql)
                seedBook(bookId = "b1", title = "Dragon Quest", libraryId = libId)
                sql.seedContributor(contributorId = "c1", name = "Dragon Author")
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("b1", "Dragon One", lib)
                seedBook("b2", "Dragon Two", lib)
                sql.seedGenre("g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                sql.linkBookGenre("b1", "g-fan")
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("b1", "Dragon Epic", lib)
                seedBook("b2", "Dragon Sci", lib)
                seedBook("b3", "Dragon Class", lib)
                sql.seedGenre("g-epic", "epic", "/fiction/fantasy/epic")
                sql.seedGenre("g-sci", "scifi", "/fiction/scifi")
                sql.seedGenre("g-fanclassics", "fanclassics", "/fiction/fantasy-classics")
                sql.linkBookGenre("b1", "g-epic")
                sql.linkBookGenre("b2", "g-sci")
                sql.linkBookGenre("b3", "g-fanclassics")
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("short", "Dragon Short", lib, durationSeconds = 3_600) // 1h
                seedBook("long", "Dragon Long", lib, durationSeconds = 36_000) // 10h
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("old", "Dragon Old", lib, publishYear = 1999)
                seedBook("new", "Dragon New", lib, publishYear = 2020)
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("match", "Dragon Match", lib, publishYear = 2020)
                seedBook("wrongYear", "Dragon WrongYear", lib, publishYear = 1990)
                seedBook("noGenre", "Dragon NoGenre", lib, publishYear = 2020)
                sql.seedGenre("g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                sql.linkBookGenre("match", "g-fan")
                sql.linkBookGenre("wrongYear", "g-fan")
                // "noGenre" deliberately has no genre link
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("b1", "Dragon One", lib)
                seedBook("b2", "Dragon Two", lib)
                sql.seedGenre("g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                sql.linkBookGenre("b1", "g-fan")
                sql.linkBookGenre("b2", "g-fan")
                sql.seedContributor("c1", "Some Author")
                sql.seedBookContributor(bookId = "b1", contributorId = "c1", role = "author", ordinal = 0)
                val service = SearchServiceImpl(db = sql, driver = driver)
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
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("b1", "The Way of Kings", lib)
                val service = SearchServiceImpl(db = sql, driver = driver)
                runTest {
                    val r = service.search(SearchQuery(text = "Kings")) as AppResult.Success<SearchResults>
                    r.data.books[0].highlight shouldBe "The Way of ${HL_START}Kings$HL_END"
                }
            }
        }

        test("facets reflect the active filter, not the unfiltered match set") {
            withSqlDatabase {
                val lib = seedLibrary(sql)
                seedBook("f1", "Dragon F1", lib)
                seedBook("f2", "Dragon F2", lib)
                seedBook("s1", "Dragon S1", lib)
                sql.seedGenre("g-fan", "fantasy", "/fiction/fantasy", "Fantasy")
                sql.seedGenre("g-sci", "scifi", "/fiction/scifi", "Sci-Fi")
                sql.linkBookGenre("f1", "g-fan")
                sql.linkBookGenre("f2", "g-fan")
                sql.linkBookGenre("s1", "g-sci")
                val service = SearchServiceImpl(db = sql, driver = driver)
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
    sql: ListenUpDatabase,
    libraryId: String = "lib1",
    path: String = "/tmp/testlibrary",
    folderId: String = "folder1",
): String {
    val now = System.currentTimeMillis()
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
    return libraryId
}

private fun SqlTestDatabases.seedBook(
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
    sql.booksQueries.insert(
        id = bookId,
        library_id = libraryId,
        folder_id = folderId,
        title = title,
        sort_title = title,
        subtitle = null,
        description = null,
        publish_year = publishYear?.toLong(),
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        // total_duration is stored in milliseconds; the contract field (durationSeconds) is in seconds.
        total_duration = durationSeconds * 1_000L,
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
        deleted_at = if (deleted) now else null,
        client_op_id = null,
    )
    // Allocate the FTS rowid mapping.
    sql.bookSearchQueries.insertMap(book_id = bookId, rowid = rowid)
    // Write the FTS row manually (book_search is contentless_delete=1, no triggers).
    driver.execute(identifier = null, sql = "DELETE FROM book_search WHERE rowid = $rowid", parameters = 0)
    driver.execute(
        identifier = null,
        sql =
            "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names) " +
                "VALUES($rowid, ?, '', '', '', '')",
        parameters = 1,
        binders = { bindString(0, title) },
    )
}

private fun ListenUpDatabase.seedContributor(
    contributorId: String,
    name: String,
    sortName: String? = null,
) {
    val now = System.currentTimeMillis()
    contributorsQueries.insert(
        id = contributorId,
        normalized_name = name.lowercase(),
        name = name,
        sort_name = sortName,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
        asin = null,
        description = null,
        image_path = null,
        image_blur_hash = null,
        birth_date = null,
        death_date = null,
        website = null,
    )
    // V19 triggers auto-populate contributor_search on INSERT — no manual FTS write needed.
}

private fun ListenUpDatabase.seedSeries(
    seriesId: String,
    name: String,
    sortName: String? = null,
) {
    val now = System.currentTimeMillis()
    seriesQueries.insert(
        id = seriesId,
        normalized_name = name.lowercase(),
        name = name,
        sort_name = sortName,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
        asin = null,
        description = null,
        cover_path = null,
        cover_blur_hash = null,
    )
    // V19 triggers auto-populate series_search on INSERT — no manual FTS write needed.
}

private fun ListenUpDatabase.seedBookContributor(
    bookId: String,
    contributorId: String,
    role: String,
    ordinal: Int,
) {
    bookContributorsQueries.insert(
        book_id = bookId,
        contributor_id = contributorId,
        role = role,
        credited_as = null,
        ordinal = ordinal.toLong(),
    )
}

/**
 * Seeds a tag row. The `tags_ai` trigger automatically populates
 * `tag_search` on INSERT, so no manual FTS write is needed.
 *
 * Soft-deleted tags are excluded from search via `t.deleted_at IS NULL` in the
 * [SearchServiceImpl.searchTags] query.
 */
private fun ListenUpDatabase.seedTag(
    tagId: String,
    name: String,
    slug: String,
    deleted: Boolean = false,
) {
    val now = System.currentTimeMillis()
    tagsQueries.insert(
        id = tagId,
        name = name,
        slug = slug,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = if (deleted) now else null,
        client_op_id = null,
    )
}

private fun ListenUpDatabase.seedGenre(
    id: String,
    slug: String,
    path: String,
    name: String = slug,
) {
    val now = System.currentTimeMillis()
    genresQueries.insert(
        id = id,
        name = name,
        slug = slug,
        path = path,
        parent_id = null,
        depth = 0L,
        sort_order = 0L,
        color = null,
        description = null,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
}

private fun ListenUpDatabase.linkBookGenre(
    bookId: String,
    genreId: String,
) {
    bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = genreId)
}

/**
 * Seeds a book_tags junction row.
 *
 * [deleted] = true simulates a soft-deleted junction that [SearchServiceImpl.searchTags]
 * excludes from the [bookCount] sub-query.
 */
private fun ListenUpDatabase.seedBookTag(
    bookId: String,
    tagId: String,
    deleted: Boolean = false,
) {
    val now = System.currentTimeMillis()
    bookTagsQueries.insert(
        id = "$bookId:$tagId",
        book_id = bookId,
        tag_id = tagId,
        created_at = now,
        updated_at = now,
        revision = 1L,
        deleted_at = if (deleted) now else null,
        client_op_id = null,
    )
}
