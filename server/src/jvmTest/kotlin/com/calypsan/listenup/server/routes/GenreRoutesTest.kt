package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.roleOf
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testAuth
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Route-level access-gate tests for [genreRoutes]'s reverse-lookup
 * `GET /api/v1/genres/{id}/books`.
 *
 * [GenreServiceImpl] is not user-scoped, so the gate lives at the route layer
 * (mirroring `PlaybackProgressRoutes`/`TagRoutes`): the returned book-id list is
 * filtered through [BookAccessPolicy.accessibleBookIds] so a book the caller can't
 * reach is simply absent — existence-preserving. ROOT/ADMIN bypass.
 */
class GenreRoutesTest :
    FunSpec({

        fun withGenreTestApp(block: suspend GenreTestScope.() -> Unit) {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val genreRepo = GenreRepository(sql, bus, registry)
                val contributorRepo = ContributorRepository(sql, bus, registry)
                val seriesRepo = SeriesRepository(sql, bus, registry)
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver)
                val bookRepo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                val service = GenreServiceImpl(genreRepo, bookRepo, reindexer, sql, BookAccessPolicy(sql, driver))
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val collectionBookRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                testApplication {
                    application {
                        install(ServerContentNegotiation) { json(contractJson) }
                        install(Resources)
                        install(Authentication) { testAuth(roleResolver = sql::roleOf) }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                genreRoutes(service)
                            }
                        }
                    }

                    val jsonClient =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                        }

                    GenreTestScope(jsonClient, sql, collectionRepo, collectionBookRepo).block()
                }
            }
        }

        test("GET /genres/{id}/books drops a book a member can't reach but keeps the accessible one") {
            withGenreTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member", UserRoleColumn.MEMBER)
                sql.seedTestBook("accessible")
                sql.seedTestBook("private")
                sql.transaction {
                    seedGenre(sql, "g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "accessible", genre_id = "g-fant")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "private", genre_id = "g-fant")
                }
                collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
                collectionBookRepo.upsert(membership("owned-col", "accessible"))
                collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(membership("stranger-col", "private"))

                val response = client.get("/api/v1/genres/g-fant/books") { bearerAuth("member") }
                response.status shouldBe HttpStatusCode.OK
                val bookIds: List<String> = response.body()
                bookIds shouldContainExactlyInAnyOrder listOf("accessible")
            }
        }

        test("GET /genres/{id}/books returns both books for an admin") {
            withGenreTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("accessible")
                sql.seedTestBook("private")
                sql.transaction {
                    seedGenre(sql, "g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "accessible", genre_id = "g-fant")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "private", genre_id = "g-fant")
                }
                collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(membership("stranger-col", "private"))

                val response = client.get("/api/v1/genres/g-fant/books") { bearerAuth("admin") }
                response.status shouldBe HttpStatusCode.OK
                val bookIds: List<String> = response.body()
                bookIds shouldContainExactlyInAnyOrder listOf("accessible", "private")
            }
        }
    })

private data class GenreTestScope(
    val client: io.ktor.client.HttpClient,
    val sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun seedGenre(
    sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    id: String,
    name: String,
    slug: String,
    path: String,
) {
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
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = null,
        client_op_id = null,
    )
}

private fun privateCollection(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
