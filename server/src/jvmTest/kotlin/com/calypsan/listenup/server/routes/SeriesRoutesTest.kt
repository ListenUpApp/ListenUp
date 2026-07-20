package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.SeriesServiceImpl
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
import com.calypsan.listenup.server.metadata.ImageStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.io.files.Path
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Route-level access-gate tests for [seriesRoutes]'s reverse-lookup
 * `GET /api/v1/series/{id}/books`.
 *
 * [SeriesServiceImpl] is explicitly not user-scoped, so the gate lives at the
 * route layer (mirroring `PlaybackProgressRoutes`/`TagRoutes`): the returned book
 * list is filtered through [BookAccessPolicy.accessibleBookIds] so a book the
 * caller can't reach is simply absent — existence-preserving. ROOT/ADMIN bypass.
 */
class SeriesRoutesTest :
    FunSpec({

        fun withSeriesTestApp(block: suspend SeriesTestScope.() -> Unit) {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributorRepo = ContributorRepository(db = sql, bus = bus, registry = registry)
                val seriesRepo = SeriesRepository(db = sql, bus = bus, registry = registry)
                val bookRepo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = GenreRepository(db = sql, bus = bus, registry = registry),
                    )
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver)
                val service = SeriesServiceImpl(seriesRepo, bookRepo, reindexer, sql, BookAccessPolicy(sql, driver))
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
                                // Image-upload deps unused by these tests; a stub ImageStorage + tmp home satisfy the signature.
                                seriesRoutes(
                                    service,
                                    Path(System.getProperty("java.io.tmpdir")),
                                    ImageStorage(HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.OK) })),
                                )
                            }
                        }
                    }

                    val jsonClient =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                        }

                    SeriesTestScope(jsonClient, sql, seriesRepo, bookRepo, collectionRepo, collectionBookRepo).block()
                }
            }
        }

        test("GET /series/{id}/books drops a book a member can't reach but keeps the accessible one") {
            withSeriesTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member", UserRoleColumn.MEMBER)
                val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                bookRepo.upsert(bookWithSeries("accessible", seriesId, "1"))
                bookRepo.upsert(bookWithSeries("private", seriesId, "2"))
                collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
                collectionBookRepo.upsert(membership("owned-col", "accessible"))
                collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(membership("stranger-col", "private"))

                val response = client.get("/api/v1/series/${seriesId.value}/books") { bearerAuth("member") }
                response.status shouldBe HttpStatusCode.OK
                val books: List<BookSyncPayload> = response.body()
                books.map { it.id } shouldContainExactlyInAnyOrder listOf("accessible")
            }
        }

        test("GET /series/{id}/books returns both books for an admin") {
            withSeriesTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                bookRepo.upsert(bookWithSeries("accessible", seriesId, "1"))
                bookRepo.upsert(bookWithSeries("private", seriesId, "2"))
                collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(membership("stranger-col", "private"))

                val response = client.get("/api/v1/series/${seriesId.value}/books") { bearerAuth("admin") }
                response.status shouldBe HttpStatusCode.OK
                val books: List<BookSyncPayload> = response.body()
                books.map { it.id } shouldContainExactlyInAnyOrder listOf("accessible", "private")
            }
        }
    })

private data class SeriesTestScope(
    val client: io.ktor.client.HttpClient,
    val sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    val seriesRepo: SeriesRepository,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun bookWithSeries(
    id: String,
    seriesId: SeriesId,
    sequence: String,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Book $id",
        sortTitle = "Book $id",
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        hasScanWarning = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series =
            listOf(
                BookSeriesPayload(
                    id = seriesId.value,
                    name = "The Stormlight Archive",
                    sequence = sequence,
                ),
            ),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

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
        id = "${collectionId}:${bookId}",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
