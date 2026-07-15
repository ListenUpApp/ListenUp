package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testAuth
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Route-level end-to-end test for `GET /api/v1/search`.
 *
 * Boots a minimal Ktor application backed by a real Flyway-migrated SQLite
 * database, mounts [searchRoutes] behind [testAuth], and exercises the full
 * stack from HTTP query params → [SearchServiceImpl] filtering/facet logic →
 * JSON response. Proves that the route correctly threads `genreSlugs` through
 * [com.calypsan.listenup.api.dto.SearchFilters], collapses non-book categories
 * when a filter is active, and returns populated facet genre buckets.
 */
class SearchRoutesFilterTest :
    FunSpec({

        test("GET /api/v1/search with genreSlugs filters to books only and returns facets") {
            withSqlDatabase {
                // Seed: library + folder (required for books FK constraints).
                val libId = "lib1"
                val folderId = "folder1"
                sql.seedTestLibraryAndFolder(libraryId = libId, folderId = folderId, folderPath = "/tmp/search-route-test")

                // Seed book b1 "Dragon Quest" in the Fantasy genre.
                seedBook(sql, bookId = "b1", title = "Dragon Quest", libraryId = libId, folderId = folderId)
                // Seed a contributor whose name also contains "Dragon" — proves
                // contributors are suppressed (not merely absent) when the filter collapses results.
                seedContributor(sql, contributorId = "c1", name = "Dragon Author")
                // Seed a genre and link it to b1.
                seedGenre(sql, id = "g-fan", slug = "fantasy", path = "/fiction/fantasy", name = "Fantasy")
                linkBookGenre(sql, bookId = "b1", genreId = "g-fan")

                val service = SearchServiceImpl(db = sql, driver = driver)
                testApplication {
                    application {
                        install(ServerContentNegotiation) { json(contractJson) }
                        install(Resources)
                        install(Authentication) { testAuth() }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                searchRoutes(service)
                            }
                        }
                    }

                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response =
                        client.get("/api/v1/search?query=Dragon&genreSlugs=fantasy") {
                            bearerAuth("u1")
                        }

                    response.status shouldBe HttpStatusCode.OK
                    val results = response.body<SearchResults>()

                    // The filtered book is returned.
                    results.books.map { it.id.value } shouldBe listOf("b1")

                    // Books-only collapse: non-book categories are empty even though
                    // "Dragon Author" would match without the genre filter.
                    results.contributors.shouldBeEmpty()
                    results.series.shouldBeEmpty()
                    results.tags.shouldBeEmpty()

                    // Facets include the fantasy genre bucket.
                    results.facets.genres shouldHaveSize 1
                    results.facets.genres[0].key shouldBe "fantasy"
                    results.facets.genres[0].count shouldBe 1
                }
            }
        }
    })

// ── seed helpers (mirrors SearchServiceImplTest private helpers) ───────────────

private fun seedBook(
    sql: ListenUpDatabase,
    bookId: String,
    title: String,
    libraryId: String,
    folderId: String,
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

private fun seedContributor(
    sql: ListenUpDatabase,
    contributorId: String,
    name: String,
) {
    val now = System.currentTimeMillis()
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
}

private fun seedGenre(
    sql: ListenUpDatabase,
    id: String,
    slug: String,
    path: String,
    name: String = slug,
) {
    val now = System.currentTimeMillis()
    sql.genresQueries.insert(
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

private fun linkBookGenre(
    sql: ListenUpDatabase,
    bookId: String,
    genreId: String,
) {
    sql.bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = genreId)
}
