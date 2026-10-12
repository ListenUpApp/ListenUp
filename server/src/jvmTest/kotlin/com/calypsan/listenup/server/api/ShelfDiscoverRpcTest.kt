package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.Shelf
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * S1 over the real wire: a discovered shelf's `coverBookIds` survive `module()`, JWT auth, the
 * per-request principal and kotlinx.rpc serialization, and they are filtered by the viewer's book
 * access exactly as the count is. [ShelfAccessTest] pins order and the cap against the service; this
 * proves the field reaches a real client and never names a book the viewer cannot open.
 */
class ShelfDiscoverRpcTest :
    FunSpec({

        suspend fun ApplicationTestBuilder.setupRoot(): DiscoverIdentity {
            val session =
                publicAuthService()
                    .setupRoot(RegisterRequest("alice@shelf-rpc.example", "x".repeat(8), "Alice"))
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                    .data
            return DiscoverIdentity(userId = session.user.id.value, token = session.accessToken.value)
        }

        suspend fun ApplicationTestBuilder.registerMember(): DiscoverIdentity {
            val registered =
                publicAuthService()
                    .register(RegisterRequest("bob@shelf-rpc.example", "y".repeat(8), "Bob"))
                    .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                    .data
                    .shouldBeInstanceOf<RegisterResult.Authenticated>()
            val session =
                publicAuthService()
                    .login(LoginRequest("bob@shelf-rpc.example", "y".repeat(8)))
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                    .data
            return DiscoverIdentity(userId = registered.session.user.id.value, token = session.accessToken.value)
        }

        fun ApplicationTestBuilder.shelvesFor(token: String): ShelfService =
            createClient {
                install(WebSockets)
                installKrpc()
            }.rpc("ws://localhost/api/rpc/authed") {
                rpcConfig { serialization { json(contractJson) } }
                bearerAuth(token)
            }.withService<ShelfService>()

        test("discoverShelves carries cover book ids over RPC, and never a book the viewer cannot open") {
            val libraryRoot = Files.createTempDirectory("listenup-shelf-discover-rpc-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val alice = setupRoot()
                    val bob = registerMember()

                    val sqlDb by application.inject<ListenUpDatabase>()
                    sqlDb.seedTestLibraryAndFolder()
                    sqlDb.seedTestBook("public-book")
                    sqlDb.seedTestBook("private-book")

                    val collections by application.inject<CollectionRepository>()
                    val collectionBooks by application.inject<CollectionBookRepository>()
                    // private-book: in Alice's private collection; Bob has no relationship to it.
                    collections.upsert(
                        CollectionSyncPayload(
                            id = "alice-private",
                            libraryId = "test-library",
                            ownerId = alice.userId,
                            name = "alice-private",
                            isInbox = false,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    collectionBooks.upsert(
                        CollectionBookSyncPayload(
                            id = "alice-private:private-book",
                            collectionId = "alice-private",
                            bookId = "private-book",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )
                    // public-book: in ALL_BOOKS, which Bob's default grant reaches.
                    val collectionService by application.inject<CollectionServiceImpl>()
                    val registry by application.inject<LibraryRegistry>()
                    val allBooksId =
                        (
                            collectionService.getOrCreateSystemCollection(
                                registry.currentLibrary().value,
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

                    // Alice (ROOT) shelves both books on a public shelf, through the real RPC.
                    val aliceShelves = shelvesFor(alice.token)
                    val shelf =
                        aliceShelves
                            .createShelf(name = "Shared Picks", isPrivate = false)
                            .shouldBeInstanceOf<AppResult.Success<Shelf>>()
                            .data
                    aliceShelves.addBookToShelf(shelf.id, BookId("private-book")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    aliceShelves.addBookToShelf(shelf.id, BookId("public-book")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val discovered =
                        shelvesFor(bob.token)
                            .discoverShelves()
                            .shouldBeInstanceOf<AppResult.Success<List<DiscoveredShelf>>>()
                            .data
                            .single()

                    discovered.shelf.bookCount shouldBe 1
                    discovered.coverBookIds shouldBe listOf("public-book")
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

/** A signed-in test user: their id and a bearer token. */
private data class DiscoverIdentity(
    val userId: String,
    val token: String,
)
