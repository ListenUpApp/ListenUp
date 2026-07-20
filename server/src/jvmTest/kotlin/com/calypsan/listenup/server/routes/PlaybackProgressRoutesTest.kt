package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.roleOf
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testAuth
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Route-level contract tests for [playbackProgressRoutes].
 *
 * Uses a minimal harness (no full `module()` / Koin startup): constructs only
 * the repository and service under test, installs the [testAuth] provider so
 * the bearer token is used verbatim as the user id, mounts [playbackProgressRoutes]
 * directly inside `authenticate(JWT_PROVIDER)`, and verifies HTTP status codes
 * and response shapes.
 *
 * Seeding is done via [PlaybackPositionRepository.recordPosition] (same path the
 * production service uses), which makes these genuine integration tests of the
 * REST surface above the substrate.
 */
class PlaybackProgressRoutesTest :
    FunSpec({

        /**
         * Runs [block] with a minimal Ktor test application wired to a real in-memory
         * [PlaybackPositionRepository]. The [testAuth] provider authenticates the bearer
         * token string verbatim as the user id.
         */
        fun withProgressTestApp(
            block: suspend ProgressTestScope.() -> Unit,
        ) {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val repo = PlaybackPositionRepository(db = sql, bus = bus, registry = registry)
                val service =
                    PlaybackProgressServiceImpl(
                        repository = repo,
                        principal = PrincipalProvider { error("unscoped — copyWith required") },
                    )
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
                val accessPolicy = BookAccessPolicy(sql, driver)

                testApplication {
                    application {
                        install(ServerContentNegotiation) { json(contractJson) }
                        install(Resources)
                        install(Authentication) { testAuth(roleResolver = sql::roleOf) }
                        routing {
                            authenticate(JWT_PROVIDER) {
                                playbackProgressRoutes(service, accessPolicy)
                            }
                        }
                    }

                    val jsonClient =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                        }

                    ProgressTestScope(jsonClient, repo, sql, collectionRepo, collectionBookRepo).block()
                }
            }
        }

        // ── GET /api/v1/playback-progress ─────────────────────────────────────

        test("GET /api/v1/playback-progress returns 200 with the authenticated user's positions") {
            withProgressTestApp {
                repo.recordPosition(
                    userId = "u1",
                    bookId = "book-a",
                    positionMs = 10_000L,
                    lastPlayedAt = 1_700_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
                repo.recordPosition(
                    userId = "u1",
                    bookId = "book-b",
                    positionMs = 20_000L,
                    lastPlayedAt = 1_700_000_000_001L,
                    finished = false,
                    playbackSpeed = 1.25f,
                    currentChapterId = null,
                )
                // u2's position must NOT appear in u1's response.
                repo.recordPosition(
                    userId = "u2",
                    bookId = "book-c",
                    positionMs = 30_000L,
                    lastPlayedAt = 1_700_000_000_002L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )

                val response = client.get("/api/v1/playback-progress") { bearerAuth("u1") }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 2
            }
        }

        test("GET /api/v1/playback-progress is user-scoped (u1 cannot see u2's positions)") {
            withProgressTestApp {
                repo.recordPosition(
                    userId = "u2",
                    bookId = "book-u2",
                    positionMs = 99_000L,
                    lastPlayedAt = 1_700_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )

                val response = client.get("/api/v1/playback-progress") { bearerAuth("u1") }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 0
            }
        }

        test("GET /api/v1/playback-progress honors limit query parameter") {
            withProgressTestApp {
                repeat(5) { i ->
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-$i",
                        positionMs = 1_000L,
                        lastPlayedAt = 1_700_000_000_000L + i,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                }

                val response = client.get("/api/v1/playback-progress?limit=3") { bearerAuth("u1") }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 3
            }
        }

        // ── POST /api/v1/playback-progress/batch ─────────────────────────────

        test("POST /api/v1/playback-progress/batch returns sparse matches for existing book ids") {
            withProgressTestApp {
                // Seed positions for book-a and book-c; request book-a, book-b, book-c → returns 2.
                repo.recordPosition(
                    userId = "u1",
                    bookId = "book-a",
                    positionMs = 5_000L,
                    lastPlayedAt = 1_700_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
                repo.recordPosition(
                    userId = "u1",
                    bookId = "book-c",
                    positionMs = 15_000L,
                    lastPlayedAt = 1_700_000_000_002L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )

                val response =
                    client.post("/api/v1/playback-progress/batch") {
                        bearerAuth("u1")
                        contentType(ContentType.Application.Json)
                        setBody(listOf("book-a", "book-b", "book-c"))
                    }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 2
            }
        }

        test("POST /api/v1/playback-progress/batch with empty body returns empty list") {
            withProgressTestApp {
                val response =
                    client.post("/api/v1/playback-progress/batch") {
                        bearerAuth("u1")
                        contentType(ContentType.Application.Json)
                        setBody(emptyList<String>())
                    }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 0
            }
        }

        test("POST /api/v1/playback-progress/batch drops a member's inaccessible book id from the response") {
            withProgressTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("reachable")
                sql.seedTestBook("forbidden")
                sql.seedTestUser("member", UserRoleColumn.MEMBER)
                // The member owns the collection holding "reachable"; "forbidden" is locked
                // in a stranger's private collection. The member has their own progress on
                // BOTH books (progress is per-user), but the gate must filter the forbidden
                // id out so it is absent from the response — identical to a book with no
                // progress, never a distinct status (no differential existence).
                collectionRepo.upsert(privateProgressCollection("owned-col", owner = "member"))
                collectionBookRepo.upsert(progressMembership("owned-col", "reachable"))
                collectionRepo.upsert(privateProgressCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(progressMembership("stranger-col", "forbidden"))
                repo.recordPosition(
                    userId = "member",
                    bookId = "reachable",
                    positionMs = 5_000L,
                    lastPlayedAt = 1_700_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
                repo.recordPosition(
                    userId = "member",
                    bookId = "forbidden",
                    positionMs = 9_000L,
                    lastPlayedAt = 1_700_000_000_001L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )

                val response =
                    client.post("/api/v1/playback-progress/batch") {
                        bearerAuth("member")
                        contentType(ContentType.Application.Json)
                        setBody(listOf("reachable", "forbidden"))
                    }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 1
                positions[0].bookId shouldBe "reachable"
                positions.none { it.bookId == "forbidden" } shouldBe true
            }
        }

        test("POST /api/v1/playback-progress/batch keeps a forbidden book id for an admin (bypass)") {
            withProgressTestApp {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("forbidden")
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                // "forbidden" is private to a stranger; the admin has no relationship to it
                // but ADMIN bypasses the filter, so their own progress on it is returned.
                collectionRepo.upsert(privateProgressCollection("stranger-col", owner = "stranger"))
                collectionBookRepo.upsert(progressMembership("stranger-col", "forbidden"))
                repo.recordPosition(
                    userId = "admin",
                    bookId = "forbidden",
                    positionMs = 9_000L,
                    lastPlayedAt = 1_700_000_000_001L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )

                val response =
                    client.post("/api/v1/playback-progress/batch") {
                        bearerAuth("admin")
                        contentType(ContentType.Application.Json)
                        setBody(listOf("forbidden"))
                    }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 1
                positions[0].bookId shouldBe "forbidden"
            }
        }

        // ── GET /api/v1/playback-progress/recently-listened ──────────────────

        test("GET /api/v1/playback-progress/recently-listened returns only unfinished positions") {
            withProgressTestApp {
                repo.recordPosition(
                    userId = "u1",
                    bookId = "in-progress",
                    positionMs = 5_000L,
                    lastPlayedAt = 1_700_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
                repo.recordPosition(
                    userId = "u1",
                    bookId = "finished",
                    positionMs = 100_000L,
                    lastPlayedAt = 1_700_000_000_001L,
                    finished = true,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )

                val response =
                    client.get("/api/v1/playback-progress/recently-listened?limit=5") { bearerAuth("u1") }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 1
                positions[0].bookId shouldBe "in-progress"
            }
        }

        // ── GET /api/v1/playback-progress/completed ───────────────────────────

        test("GET /api/v1/playback-progress/completed returns only finished positions") {
            withProgressTestApp {
                repo.recordPosition(
                    userId = "u1",
                    bookId = "in-progress",
                    positionMs = 5_000L,
                    lastPlayedAt = 1_700_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )
                repo.recordPosition(
                    userId = "u1",
                    bookId = "finished",
                    positionMs = 100_000L,
                    lastPlayedAt = 1_700_000_000_001L,
                    finished = true,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                )

                val response =
                    client.get("/api/v1/playback-progress/completed") { bearerAuth("u1") }
                response.status shouldBe HttpStatusCode.OK
                val positions: List<PlaybackPositionSyncPayload> = response.body()
                positions shouldHaveSize 1
                positions[0].bookId shouldBe "finished"
            }
        }
    })

/** Test scope giving access to the HTTP [client] and the raw [repo] for seeding. */
private data class ProgressTestScope(
    val client: io.ktor.client.HttpClient,
    val repo: PlaybackPositionRepository,
    val sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

/** Builds a private (non-global-access, non-inbox) collection owned by [owner]. */
private fun privateProgressCollection(
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

/** Builds a `collection_books` membership row placing [bookId] in [collectionId]. */
private fun progressMembership(
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
