package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreTable
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
import com.calypsan.listenup.server.testing.withInMemoryDatabase
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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val genreRepo = GenreRepository(db, bus, registry)
                val contributorRepo = ContributorRepository(db, bus, registry)
                val seriesRepo = SeriesRepository(db, bus, registry)
                val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
                val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)
                val bookRepo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                    )
                val service = GenreServiceImpl(genreRepo, bookRepo, reindexer, db)
                val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
                val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry)
                val accessPolicy = BookAccessPolicy(db)

                testApplication {
                    application {
                        install(ServerContentNegotiation) { json(contractJson) }
                        install(Resources)
                        install(Authentication) { testAuth(roleResolver = db::roleOf) }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                genreRoutes(service, accessPolicy)
                            }
                        }
                    }

                    val jsonClient =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                        }

                    GenreTestScope(jsonClient, db, collectionRepo, collectionBookRepo).block()
                }
            }
        }

        test("GET /genres/{id}/books drops a book a member can't reach but keeps the accessible one") {
            withGenreTestApp {
                db.seedTestLibraryAndFolder()
                db.seedTestUser("member", UserRoleColumn.MEMBER)
                db.seedTestBook("accessible")
                db.seedTestBook("private")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    BookGenreTable.insertIfAbsent("accessible", "g-fant")
                    BookGenreTable.insertIfAbsent("private", "g-fant")
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
                db.seedTestLibraryAndFolder()
                db.seedTestUser("admin", UserRoleColumn.ADMIN)
                db.seedTestBook("accessible")
                db.seedTestBook("private")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    BookGenreTable.insertIfAbsent("accessible", "g-fant")
                    BookGenreTable.insertIfAbsent("private", "g-fant")
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
    val db: Database,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
) {
    GenreTable.insert {
        it[GenreTable.id] = id
        it[GenreTable.name] = name
        it[GenreTable.slug] = slug
        it[GenreTable.path] = path
        it[GenreTable.parentId] = null
        it[GenreTable.depth] = 0
        it[GenreTable.sortOrder] = 0
        it[GenreTable.color] = null
        it[GenreTable.description] = null
        it[GenreTable.revision] = 0L
        it[GenreTable.createdAt] = 0L
        it[GenreTable.updatedAt] = 0L
        it[GenreTable.deletedAt] = null
        it[GenreTable.clientOpId] = null
    }
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
        isGlobalAccess = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
