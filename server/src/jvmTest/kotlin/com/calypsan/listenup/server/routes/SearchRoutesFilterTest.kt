package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver
import com.calypsan.listenup.server.testing.testAuth
import com.calypsan.listenup.server.testing.withInMemoryDatabase
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
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
            withInMemoryDatabase {
                val db = this
                // Seed: library + folder (required for books FK constraints).
                val now = System.currentTimeMillis()
                val libId = "lib1"
                val folderId = "folder1"
                transaction(db) {
                    LibraryTable.insert {
                        it[LibraryTable.id] = libId
                        it[LibraryTable.name] = "Test Library"
                        it[LibraryTable.metadataPrecedence] = "embedded"
                        it[LibraryTable.createdAt] = now
                        it[LibraryTable.updatedAt] = now
                        it[LibraryTable.revision] = 0L
                        it[LibraryTable.deletedAt] = null
                    }
                    LibraryFolderTable.insert {
                        it[LibraryFolderTable.id] = folderId
                        it[LibraryFolderTable.libraryId] = libId
                        it[LibraryFolderTable.rootPath] = "/tmp/search-route-test"
                        it[LibraryFolderTable.createdAt] = now
                        it[LibraryFolderTable.updatedAt] = now
                        it[LibraryFolderTable.revision] = 0L
                        it[LibraryFolderTable.deletedAt] = null
                    }
                }

                // Seed book b1 "Dragon Quest" in the Fantasy genre.
                seedBook(db, bookId = "b1", title = "Dragon Quest", libraryId = libId, folderId = folderId)
                // Seed a contributor whose name also contains "Dragon" — proves
                // contributors are suppressed (not merely absent) when the filter collapses results.
                seedContributor(db, contributorId = "c1", name = "Dragon Author")
                // Seed a genre and link it to b1.
                seedGenre(db, id = "g-fan", slug = "fantasy", path = "/fiction/fantasy", name = "Fantasy")
                linkBookGenre(db, bookId = "b1", genreId = "g-fan")

                val service = SearchServiceImpl(db = db.asSqlDatabase(), driver = db.asSqlDriver())
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
    db: org.jetbrains.exposed.v1.jdbc.Database,
    bookId: String,
    title: String,
    libraryId: String,
    folderId: String,
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
    db: org.jetbrains.exposed.v1.jdbc.Database,
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
