package com.calypsan.listenup.client.ratings

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.BookRatingRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.DomainPendingOperationSender
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.OutboxOpSender
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.RpcSyncStreamClient
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.testing.awaitUntil
import com.calypsan.listenup.client.data.sync.testing.registerTestSyncDomains
import com.calypsan.listenup.client.data.sync.testing.testAuth
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.domain.readers.flattenToLines
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookRatingServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.BookRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.createSyncStreamService
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.withService
import org.koin.core.context.GlobalContext

private const val ALICE = "alice"
private const val BOB = "bob"
private const val BOOK = "book-1"

/**
 * The spec's end-to-end promise for listener ratings, in one process: Alice rates a book through
 * the real client [BookRatingRepositoryImpl] (Room first, then the outbox), her engine drains the op
 * into the real server [BookRatingServiceImpl] + [BookRatingRepository], and Bob's engine — a second
 * client with its own Room, connected over the real RPC firehose and cursored pull — ends up with the
 * new listener average in his `book_ratings` mirror and Alice's stars and note on her reader line.
 *
 * Follows the Tag/Collection harness shape: a `testApplication` mounting the real
 * [SyncStreamService], with the outbox sender calling the in-process service directly (as the sibling
 * harnesses do). Every principal is ROOT via [testAuth] — the access gate on ratings has its own
 * server-side coverage (`BookRatingsFirehoseAccessTest`); this test is about the rating travelling.
 * Alice has never played the book here, so the readership RPC answers empty and her line is the
 * rated-only one, named from the `public_profiles` domain the server also syncs to Bob.
 */
class BookRatingTwoListenerE2ETest :
    FunSpec({

        test("a rating travels from one listener to another's reader line") {
            withTwoListenersAgainstServer {
                alice.engine.start(currentUserId = ALICE)
                bob.engine.start(currentUserId = BOB)

                aliceRatings
                    .rate(BOOK, halfStars = 9, note = "  Wept at the ending.  ")
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Bob's Room gains the rating once Alice's outbox has drained and the server has
                // fanned the new row out to him.
                awaitUntil { bob.database.bookRatingDao().find(BOOK, ALICE) != null }
                alice.queue.observeDeadLetterCount().first() shouldBe 0

                val average =
                    bob.database
                        .bookRatingDao()
                        .observeAverages()
                        .first()
                        .single { it.bookId == BOOK }
                average.averageHalfStars shouldBe 9.0
                average.ratingCount shouldBe 1

                awaitUntil {
                    bob.database
                        .publicProfileDao()
                        .observeAll()
                        .first()
                        .any { it.id == ALICE }
                }
                val aliceLine =
                    flattenToLines(bobReaders.observeReadersFor(BOOK).first { it.readers.isNotEmpty() }.readers)
                        .single { it.userId == ALICE }
                aliceLine.name shouldBe "Alice"
                aliceLine.isYou shouldBe false
                aliceLine.kind shouldBe ReaderLineKind.Rated
                val rating = aliceLine.rating.shouldNotBeNull()
                rating.halfStars shouldBe 9
                rating.note shouldBe "Wept at the ending."
            }
        }
    })

/** One listener's client: its own Room, outbox and sync engine. */
private class ListenerClient(
    val database: ListenUpDatabase,
    val queue: PendingOperationQueue,
    val engine: SyncEngine,
)

private class TwoListenerScope(
    val alice: ListenerClient,
    val bob: ListenerClient,
    val aliceRatings: BookRatingRepositoryImpl,
    val bobReaders: BookReadersRepositoryImpl,
)

private fun withTwoListenersAgainstServer(block: suspend TwoListenerScope.() -> Unit) {
    testApplication {
        val tmp = Files.createTempFile("listenup-ratings-e2e-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val serverDriver = DriverFactory().createDriver(tmp.absolutePath)
        val serverSqlDb = ServerSqlDatabase(serverDriver)
        val bus = ChangeBus()
        val syncRegistry = SyncRegistry()
        seedRatingE2ERows(serverDriver)

        val bookAccessPolicy = BookAccessPolicy(serverSqlDb, serverDriver)
        val ratingRepo = BookRatingRepository(serverSqlDb, bus, syncRegistry, driver = serverDriver)
        val profiles = PublicProfileMaintainer(serverSqlDb, PublicProfileRepository(serverSqlDb, bus, syncRegistry))
        profiles.refresh(ALICE)
        profiles.refresh(BOB)
        val aliceRatingService =
            BookRatingServiceImpl(
                ratings = ratingRepo,
                accessPolicy = bookAccessPolicy,
                principal = PrincipalProvider { UserPrincipal(UserId(ALICE), SessionId("s-$ALICE"), UserRole.ROOT) },
            )

        application {
            install(ServerKrpc)
            install(Authentication) { testAuth(defaultUserId = BOB) }
            routing {
                authenticate(JWT_PROVIDER) {
                    serverRpc("/api/rpc/authed") {
                        rpcConfig { serialization { krpcJson(contractJson) } }
                        registerService<SyncStreamService> {
                            val p = call.userPrincipalOrNull() ?: error("authed RPC mount reached without a principal")
                            guard(createSyncStreamService(bus, syncRegistry, { bookAccessPolicy }, PrincipalProvider { p }))
                        }
                    }
                }
            }
        }

        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aliceDb = createInMemoryTestDatabase()
        val bobDb = createInMemoryTestDatabase()
        try {
            val ratingChannel = RpcChannel.forTest<com.calypsan.listenup.api.BookRatingService>(aliceRatingService)
            val aliceQueue =
                PendingOperationQueue(
                    dao = aliceDb.pendingOperationV2Dao(),
                    sender =
                        DomainPendingOperationSender(
                            mapOf(
                                OutboxChannels.BookRatings.name to
                                    OutboxOpSender(OutboxChannels.BookRatings) { _, mutation ->
                                        when (mutation) {
                                            is BookRatingMutation.Set -> {
                                                ratingChannel.call {
                                                    it.rate(
                                                        BookId(mutation.bookId),
                                                        RateBookRequest(
                                                            candidateId = mutation.candidateId,
                                                            halfStars = mutation.halfStars,
                                                            note = mutation.note,
                                                        ),
                                                    )
                                                }
                                            }

                                            is BookRatingMutation.Clear -> {
                                                ratingChannel.call { it.clearRating(BookId(mutation.bookId)) }
                                            }
                                        }
                                    },
                            ),
                        ),
                )
            val alice = listenerClient(ALICE, aliceDb, aliceQueue, clientScope)
            val bobQueue = PendingOperationQueue(bobDb.pendingOperationV2Dao(), DomainPendingOperationSender(emptyMap()))
            val bob = listenerClient(BOB, bobDb, bobQueue, clientScope)
            val aliceSession = FakeAuthSession(userId = ALICE)
            try {
                TwoListenerScope(
                    alice = alice,
                    bob = bob,
                    aliceRatings =
                        BookRatingRepositoryImpl(
                            dao = aliceDb.bookRatingDao(),
                            externalRatingDao = aliceDb.bookExternalRatingDao(),
                            offlineEditor = OfflineEditor(aliceQueue, RoomTransactionRunner(aliceDb), aliceSession),
                            authSession = aliceSession,
                            ratingChannel = ratingChannel,
                        ),
                    bobReaders = bobReaders(bobDb),
                ).block()
            } finally {
                alice.engine.stopAndJoin()
                bob.engine.stopAndJoin()
            }
        } finally {
            clientScope.cancel()
            aliceDb.close()
            bobDb.close()
            if (GlobalContext.getKoinApplicationOrNull() != null) GlobalContext.stopKoin()
        }
    }
}

/**
 * A listener's [SyncEngine] over the harness's in-process server: the real catalog's handlers, the
 * catch-up pull, digest and RPC firehose on one proxy whose socket carries [userId] as its bearer.
 */
private suspend fun ApplicationTestBuilder.listenerClient(
    userId: String,
    db: ListenUpDatabase,
    queue: PendingOperationQueue,
    scope: CoroutineScope,
): ListenerClient {
    val registry = ClientSyncDomainRegistry()
    registerTestSyncDomains(db = db, registry = registry, authSession = FakeAuthSession(userId = userId))
    val httpClient: HttpClient =
        createClient {
            installKrpc()
            defaultRequest { bearerAuth(userId) }
        }
    val syncChannel =
        RpcChannel.forTest(
            httpClient
                .rpc("ws://localhost/api/rpc/authed") { rpcConfig { serialization { krpcJson(contractJson) } } }
                .withService<SyncStreamService>(),
        )
    val state = SyncEngineState()
    val store = SyncCursorStore(db.syncCursorDao())
    val catchUp = SyncCatchUpClient(channel = syncChannel, store = store, transactionRunner = RoomTransactionRunner(db))
    var engineRef: SyncEngine? = null
    val dispatcher =
        SyncEventDispatcher(
            registry = registry,
            state = state,
            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
            onCursorStale = { checkNotNull(engineRef).handleCursorStale() },
        )
    val engine =
        SyncEngine(
            registry = registry,
            queue = queue,
            state = state,
            store = store,
            catchUp = catchUp,
            syncStreamClient = RpcSyncStreamClient(channel = syncChannel, state = state, scope = scope),
            reconciler = SyncReconciler(registry, store, DomainDigestClient(channel = syncChannel), catchUp),
            dispatcher = dispatcher,
            presenceRefreshSignal = PresenceRefreshSignal(),
            scope = scope,
        ).also { engineRef = it }
    return ListenerClient(db, queue, engine)
}

/**
 * Bob's real readers repository over his Room. The readership RPC answers empty — Alice has never
 * played the book on this server — so her line can only come from the rating and her synced profile.
 */
private fun bobReaders(db: ListenUpDatabase): BookReadersRepositoryImpl {
    val social =
        mock<SocialService> {
            everySuspend { bookReadership(any()) } returns AppResult.Success(BookReadership(emptyList()))
        }
    val bobUser =
        User(
            id = UserId(BOB),
            email = "$BOB@x",
            displayName = "Bob",
            isAdmin = false,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )
    val users =
        object : UserRepository {
            override fun observeCurrentUser(): Flow<User?> = flowOf(bobUser)

            override fun observeIsAdmin(): Flow<Boolean> = flowOf(false)

            override suspend fun getCurrentUser(): User = bobUser

            override suspend fun saveUser(user: User) = Unit

            override suspend fun clearUsers() = Unit

            override suspend fun refreshCurrentUser(): User = bobUser
        }
    return BookReadersRepositoryImpl(
        channel = RpcChannel.forTest(social),
        presence = PresenceRefreshSignal(),
        userRepository = users,
        readershipDao = db.bookReadershipDao(),
        ratingDao = db.bookRatingDao(),
        publicProfileDao = db.publicProfileDao(),
    )
}

/** The library, folder and book a rating FKs to, and the two listeners. */
private fun seedRatingE2ERows(driver: SqlDriver) {
    val now = System.currentTimeMillis()
    driver.execute(
        null,
        "INSERT INTO libraries(id, name, created_at, updated_at, revision) VALUES ('lib', 'Library', $now, $now, 0)",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO library_folders(id, library_id, root_path, created_at, updated_at, revision) " +
            "VALUES ('folder', 'lib', '/tmp/lib', $now, $now, 0)",
        0,
    )
    driver.execute(
        null,
        "INSERT INTO books(id, library_id, title, total_duration, root_rel_path, scanned_at, revision, " +
            "created_at, updated_at) VALUES ('$BOOK', 'lib', 'The Book', 0, '$BOOK', $now, 0, $now, $now)",
        0,
    )
    for ((id, name) in listOf(ALICE to "Alice", BOB to "Bob")) {
        driver.execute(
            null,
            "INSERT INTO users(id, email, email_normalized, password_hash, role, display_name, status, " +
                "created_at, updated_at) VALUES ('$id', '$id@x', '$id@x', 'x', 'MEMBER', '$name', 'ACTIVE', $now, $now)",
            0,
        )
    }
}
