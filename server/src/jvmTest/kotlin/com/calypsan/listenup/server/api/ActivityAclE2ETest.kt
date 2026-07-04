package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.activity.ActivityEvent
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * The crown-jewel ACL proof for the activity feed, wired full-stack in a single process: two real
 * users driven over the authed kotlinx.rpc [ActivityService] surface, asserting that the book-access
 * boundary holds end to end — through `module()`, JWT auth, the per-request principal binding, and
 * `BookAccessPolicy`.
 *
 * Where [ActivityServiceTest] proves the service contract + ACL filter against in-memory
 * repositories, this proves the *wiring*: that the principal a JWT carries reaches
 * [ActivityServiceImpl] via the `registerScoped<ActivityService>` block, and that the same ACL
 * filter omits a private-book activity over the real wire — not just in a hand-built service
 * instance.
 *
 * Two users:
 *  - **A** (ROOT, via `/auth/setup`) records a `finished_book` on a public book (placed in the
 *    library's `ALL_BOOKS` system collection — the public substrate under the pure-union rule),
 *    a `finished_book` on a book gated into A's own private collection, and a non-book
 *    `shelf_created`.
 *  - **B** (MEMBER, via `/auth/register` under OPEN policy) is the viewer. B is registered through
 *    the real auth flow, so B holds the default `ALL_BOOKS` grant and reaches the public book under
 *    pure union; B has no relationship to A's private collection, so `BookAccessPolicy` denies it.
 *
 * Driving the read as B over RPC, the test asserts `feed()`:
 *  - contains the `public-book` `finished_book`,
 *  - omits the `private-book` activity entirely (`none { bookId == "private-book" }`),
 *  - contains the non-book `shelf_created` (always shown — non-book activity is ACL-exempt).
 *
 * Activities are recorded through the real [ActivityRecorder.record] write-path resolved from the
 * running application's Koin — the same path the seven recording hooks drive — and A is given a live
 * `public_profiles` identity via [PublicProfileMaintainer.refresh], else her activity has no identity
 * to join and the service drops it.
 */
class ActivityAclE2ETest :
    FunSpec({

        /** Runs first-user setup; returns A's ROOT user id and bearer token. */
        suspend fun HttpClient.setupRoot(): ActivityAuthIdentity {
            val session =
                post("/api/v1/auth/setup") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("alice@activity-e2e.example", "x".repeat(8), "Alice"))
                }.body<AppResult<AuthSession>>()
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                    .data
            return ActivityAuthIdentity(userId = session.user.id.value, token = session.accessToken.value)
        }

        /** Registers a second user (MEMBER under OPEN policy); returns B's id and bearer token. */
        suspend fun HttpClient.registerMember(): ActivityAuthIdentity {
            val registered =
                post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("bob@activity-e2e.example", "y".repeat(8), "Bob"))
                }.body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                    .data
                    .shouldBeInstanceOf<RegisterResult.Authenticated>()
            // Re-login so the bearer token is independent of the register-issued one.
            val session =
                post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("bob@activity-e2e.example", "y".repeat(8)))
                }.body<AppResult<AuthSession>>()
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                    .data
            return ActivityAuthIdentity(userId = registered.session.user.id.value, token = session.accessToken.value)
        }

        /** Opens an authed [ActivityService] proxy bound to [token]'s principal. */
        suspend fun HttpClient.activityServiceFor(token: String): ActivityService =
            rpc("ws://localhost/api/rpc/authed") {
                rpcConfig { serialization { json(contractJson) } }
                bearerAuth(token)
            }.withService<ActivityService>()

        /**
         * Gates [bookId] into a private collection owned by [ownerId] so it is invisible to any
         * non-admin member without an explicit relationship — the same gating approach as
         * [ActivityServiceTest]'s `makeBookInaccessible`, driven through the real collection
         * repositories resolved from the application's Koin.
         */
        suspend fun gatePrivate(
            collections: CollectionRepository,
            collectionBooks: CollectionBookRepository,
            bookId: String,
            collectionId: String,
            ownerId: String,
        ) {
            collections.upsert(
                CollectionSyncPayload(
                    id = collectionId,
                    libraryId = "test-library",
                    ownerId = ownerId,
                    name = collectionId,
                    isInbox = false,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
            collectionBooks.upsert(
                CollectionBookSyncPayload(
                    collectionId = collectionId,
                    bookId = bookId,
                    createdAt = 0L,
                    revision = 0L,
                ),
            )
        }

        test("crown jewel: viewer sees the accessible-book activity over RPC, never the private one, always the non-book one") {
            val libraryRoot = Files.createTempDirectory("listenup-activity-acl-e2e-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val alice = restClient.setupRoot()
                    val bob = restClient.registerMember()

                    // ── Seed library + two books: one public (joins ALL_BOOKS), one gated private ──
                    seedTestLibraryAndFolder()
                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestBook("public-book")
                    sql.seedTestBook("private-book")

                    val collections by application.inject<CollectionRepository>()
                    val collectionBooks by application.inject<CollectionBookRepository>()
                    gatePrivate(
                        collections = collections,
                        collectionBooks = collectionBooks,
                        bookId = "private-book",
                        collectionId = "alice-private",
                        ownerId = alice.userId,
                    )

                    // ── Make public-book actually public under pure union: place it in the
                    //    library's ALL_BOOKS system collection. B (registered via the real auth
                    //    flow) holds the default ALL_BOOKS grant on this same library, so the
                    //    grant branch of BookAccessPolicy reaches the book — there is no
                    //    uncollected→public fallback anymore. Resolve ALL_BOOKS for the bootstrap
                    //    library (the one B's default grant targets, per DefaultAllBooksGrantIssuer),
                    //    then add the membership directly via the repo (a system write, no principal).
                    val collectionService by application.inject<CollectionServiceImpl>()
                    val registry by application.inject<LibraryRegistry>()
                    val bootstrapLibraryId = registry.currentLibrary().value
                    val allBooksId =
                        (
                            collectionService.getOrCreateSystemCollection(
                                bootstrapLibraryId,
                                SystemCollectionType.ALL_BOOKS,
                            ) as AppResult.Success
                        ).data.id.value
                    collectionBooks.upsert(
                        CollectionBookSyncPayload(
                            collectionId = allBooksId,
                            bookId = "public-book",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )

                    // ── A needs a live public_profiles identity, else her activity is dropped. ──
                    val profiles by application.inject<PublicProfileMaintainer>()
                    profiles.refresh(alice.userId)

                    // ── A records activity through the real ActivityRecorder write-path. ──────
                    val activities by application.inject<ActivityRecorder>()
                    activities.record(userId = alice.userId, type = ActivityType.FINISHED_BOOK, bookId = "public-book")
                    activities.record(userId = alice.userId, type = ActivityType.FINISHED_BOOK, bookId = "private-book")
                    activities.record(
                        userId = alice.userId,
                        type = ActivityType.SHELF_CREATED,
                        shelfId = "alice-shelf",
                        shelfName = "Alice's Picks",
                    )

                    // ── Drive the read as B over the real authed RPC surface. ──────────────────
                    val rpcClient =
                        createClient {
                            install(WebSockets)
                            installKrpc()
                        }
                    val activityService = rpcClient.activityServiceFor(bob.token)

                    val feed =
                        activityService
                            .feed()
                            .shouldBeInstanceOf<AppResult.Success<List<ActivityEvent>>>()
                            .data

                    // The accessible-book activity is present.
                    feed.any { it.bookId == "public-book" && it.type == ActivityType.FINISHED_BOOK } shouldBe true
                    // Crown jewel: the private-book activity is omitted entirely.
                    feed.none { it.bookId == "private-book" } shouldBe true
                    // The non-book activity is always shown (ACL-exempt).
                    feed.any { it.type == ActivityType.SHELF_CREATED && it.shelfId == "alice-shelf" } shouldBe true
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

/** A registered user's server-issued id and a bearer token for the authed surfaces. */
private data class ActivityAuthIdentity(
    val userId: String,
    val token: String,
)
