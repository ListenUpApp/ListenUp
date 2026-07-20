package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * The crown-jewel ACL proof, wired full-stack in a single process: two real users
 * driven over the authed kotlinx.rpc [SocialService] surface, asserting that the
 * book-access boundary holds end to end — through `module()`, JWT auth, the
 * per-request principal binding, and `BookAccessPolicy`.
 *
 * Where [SocialServiceTest] proves the service contract against in-memory repositories,
 * this proves the *wiring*: that the principal a JWT carries reaches [SocialServiceImpl]
 * via the `registerScoped<SocialService>` block, and that the same ACL filter excludes a
 * private-book session over the real wire — not just in a hand-built service instance.
 *
 * Two users:
 *  - **A** (ROOT, via `/auth/setup`) listens to a public book (placed in the library's
 *    `ALL_BOOKS` system collection — the public substrate under the pure-union rule) and a
 *    book gated into A's own private collection.
 *  - **B** (MEMBER, via `/auth/register` under OPEN policy) is the viewer. B is registered
 *    through the real auth flow, so B holds the default `ALL_BOOKS` grant and reaches the
 *    public book under pure union; B has no relationship to A's private collection, so
 *    `BookAccessPolicy` denies B the private book.
 *
 * Driving the reads as B over RPC, the test asserts:
 *  - `currentlyListening()` returns the accessible-book session and **omits** the
 *    private-book one (both present-and-absent asserted).
 *  - `bookReadership(accessibleBook)` lists A (seeded a finish row).
 *  - `bookReadership(privateBook)` returns `AppResult.Failure(SocialError.NotFound)` —
 *    never revealing the book exists.
 *
 * Presence is server-derived and never synced, so A's sessions are seeded through the
 * real [ActiveSessionRepository.startOrRefresh] write-path resolved from the running
 * application's Koin — the same path `recordPosition` drives — rather than replaying a
 * full `recordPosition` call (which would require seeding audio files + chapters the ACL
 * invariant under test does not depend on).
 */
class SocialAclE2ETest :
    FunSpec({

        /** Runs first-user setup; returns A's ROOT user id and bearer token. */
        suspend fun HttpClient.setupRoot(): AuthIdentity {
            val session =
                post("/api/v1/auth/setup") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("alice@social-e2e.example", "x".repeat(8), "Alice"))
                }.body<AppResult<AuthSession>>()
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                    .data
            return AuthIdentity(userId = session.user.id.value, token = session.accessToken.value)
        }

        /** Registers a second user (MEMBER under OPEN policy); returns B's id and bearer token. */
        suspend fun HttpClient.registerMember(): AuthIdentity {
            val registered =
                post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("bob@social-e2e.example", "y".repeat(8), "Bob"))
                }.body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                    .data
                    .shouldBeInstanceOf<RegisterResult.Authenticated>()
            // Re-login so the bearer token is independent of the register-issued one.
            val session =
                post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("bob@social-e2e.example", "y".repeat(8)))
                }.body<AppResult<AuthSession>>()
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                    .data
            return AuthIdentity(userId = registered.session.user.id.value, token = session.accessToken.value)
        }

        /** Opens an authed [SocialService] proxy bound to [token]'s principal. */
        suspend fun HttpClient.socialServiceFor(token: String): SocialService =
            rpc("ws://localhost/api/rpc/authed") {
                rpcConfig { serialization { json(contractJson) } }
                bearerAuth(token)
            }.withService<SocialService>()

        /**
         * Gates [bookId] into a private collection owned by [ownerId] so it is invisible to
         * any non-admin member without an explicit relationship — the same gating approach
         * as [SocialServiceTest.makeBookInaccessible], driven through the real collection
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
                    id = "$collectionId:$bookId",
                    collectionId = collectionId,
                    bookId = bookId,
                    createdAt = 0L,
                    revision = 0L,
                ),
            )
        }

        test("crown jewel: viewer sees the accessible-book session over RPC and never the private one") {
            val libraryRoot = Files.createTempDirectory("listenup-social-acl-e2e-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val alice = restClient.setupRoot()
                    val bob = restClient.registerMember()

                    // ── Seed library + two books: one public (joins ALL_BOOKS), one gated private ──
                    val sqlDb by application.inject<ListenUpDatabase>()
                    sqlDb.seedTestLibraryAndFolder()
                    sqlDb.seedTestBook("public-book")
                    sqlDb.seedTestBook("private-book")

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
                            id = "$allBooksId:public-book",
                            collectionId = allBooksId,
                            bookId = "public-book",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )

                    // ── A needs a live public_profiles identity, else her session is dropped. ──
                    val profiles by application.inject<PublicProfileMaintainer>()
                    profiles.refresh(alice.userId)

                    // ── A starts live presence on both books (real server-derived write-path). ──
                    val sessions by application.inject<ActiveSessionRepository>()
                    sessions.startOrRefresh(userId = alice.userId, bookId = "public-book")
                    sessions.startOrRefresh(userId = alice.userId, bookId = "private-book")

                    // ── A also has a persistent completion of the public book, so she appears in
                    //    its readership (which reads book_reads + in-progress positions, not presence). ──
                    val reads by application.inject<BookReadsRepository>()
                    reads.recordRead(
                        id = "alice-public-finish",
                        userId = alice.userId,
                        bookId = "public-book",
                        finishedAt = 1_000L,
                        source = "playback",
                    )

                    // ── Drive the reads as B over the real authed RPC surface. ────────────────
                    val rpcClient =
                        createClient {
                            install(WebSockets)
                            installKrpc()
                        }
                    val social = rpcClient.socialServiceFor(bob.token)

                    // currentlyListening: only the public-book session; the private one is omitted.
                    val listening =
                        social
                            .currentlyListening()
                            .shouldBeInstanceOf<AppResult.Success<List<CurrentlyListeningSession>>>()
                            .data
                    listening shouldHaveSize 1
                    listening.single().bookId shouldBe "public-book"
                    listening.single().userId shouldBe alice.userId
                    listening.none { it.bookId == "private-book" } shouldBe true

                    // bookReadership on the accessible book: A is listed (via her finish row).
                    val readers =
                        social
                            .bookReadership(BookId("public-book"))
                            .shouldBeInstanceOf<AppResult.Success<BookReadership>>()
                            .data
                            .readers
                    readers shouldHaveSize 1
                    readers.single().userId shouldBe alice.userId
                    readers.single().finishes shouldBe listOf(1_000L)

                    // bookReadership on the private book: NotFound — never revealing it exists.
                    val denied = social.bookReadership(BookId("private-book"))
                    denied.shouldBeInstanceOf<AppResult.Failure>()
                    denied.error.shouldBeInstanceOf<SocialError.NotFound>()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

/** A registered user's server-issued id and a bearer token for the authed surfaces. */
private data class AuthIdentity(
    val userId: String,
    val token: String,
)
