package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.ContributorServiceImpl
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
 * Route-level access-gate tests for [contributorRoutes]'s reverse-lookup
 * `GET /api/v1/contributors/{id}/books`.
 *
 * The service ([ContributorServiceImpl]) is not user-scoped, so the access gate
 * lives at the route layer (mirroring `PlaybackProgressRoutes`/`TagRoutes`):
 * the returned book list is filtered through [BookAccessPolicy.accessibleBookIds]
 * so a book the caller can't reach is simply absent — existence-preserving, the
 * same shape as a contributor that legitimately has no accessible books. ROOT/ADMIN
 * bypass the filter.
 */
class ContributorRoutesTest :
    FunSpec({

        fun withContributorTestApp(block: suspend ContributorTestScope.() -> Unit) {
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
                val service =
                    ContributorServiceImpl(contributorRepo, bookRepo, reindexer, sql, BookAccessPolicy(sql, driver))
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
                                contributorRoutes(
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

                    ContributorTestScope(
                        jsonClient,
                        sql,
                        contributorRepo,
                        bookRepo,
                        collectionRepo,
                        collectionBookRepo,
                    ).block()
                }
            }
        }

        test("GET /contributors/{id}/books drops a book a member can't reach but keeps the accessible one") {
            withContributorTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member", UserRoleColumn.MEMBER)
                val contributorId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                bookRepo.upsert(bookWithContributor("accessible", contributorId))
                bookRepo.upsert(bookWithContributor("private", contributorId))
                // accessible: in a collection the member owns. private: locked to a stranger.
                collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
                collectionBookRepo.upsert(membership("owned-col", "accessible"))
                collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(membership("stranger-col", "private"))

                val response = client.get("/api/v1/contributors/${contributorId.value}/books") { bearerAuth("member") }
                response.status shouldBe HttpStatusCode.OK
                val books: List<BookSyncPayload> = response.body()
                books.map { it.id } shouldContainExactlyInAnyOrder listOf("accessible")
            }
        }

        test("GET /contributors/{id}/books returns both books for an admin") {
            withContributorTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                val contributorId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                bookRepo.upsert(bookWithContributor("accessible", contributorId))
                bookRepo.upsert(bookWithContributor("private", contributorId))
                collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(membership("stranger-col", "private"))

                val response = client.get("/api/v1/contributors/${contributorId.value}/books") { bearerAuth("admin") }
                response.status shouldBe HttpStatusCode.OK
                val books: List<BookSyncPayload> = response.body()
                books.map { it.id } shouldContainExactlyInAnyOrder listOf("accessible", "private")
            }
        }
    })

private data class ContributorTestScope(
    val client: io.ktor.client.HttpClient,
    val sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    val contributorRepo: ContributorRepository,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun bookWithContributor(
    id: String,
    contributorId: ContributorId,
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
        contributors =
            listOf(
                BookContributorPayload(
                    id = contributorId.value,
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
        series = emptyList(),
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
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
