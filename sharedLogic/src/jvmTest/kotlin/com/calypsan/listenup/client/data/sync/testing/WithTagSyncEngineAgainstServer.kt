package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.DomainPendingOperationSender
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.SyncSseClient
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.syncRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Test scope for [TagSyncE2ETest][com.calypsan.listenup.client.data.sync.TagSyncE2ETest].
 *
 * Exposes the real server-side [TagRepository] and [BookTagRepository] for
 * triggering writes that publish SSE events, and the client [ListenUpDatabase]
 * for asserting that events landed in Room.
 */
internal data class TagSyncEngineScope(
    val engine: SyncEngine,
    val tagRepo: TagRepository,
    val bookTagRepo: BookTagRepository,
    val clientDatabase: ListenUpDatabase,
)

/**
 * Boots a real `:server` test application AND the client engine in one process,
 * with a real in-memory Room DB on the client side.
 *
 * Unlike [withClientSyncEngineAgainstServer], this harness registers the real
 * [com.calypsan.listenup.client.data.sync.domains.tagsDomain] handler and
 * [com.calypsan.listenup.client.data.sync.domains.bookTagsDomain] instead of
 * [RecordingTagSyncDomainHandler]. This lets
 * tests assert that tag and book_tag
 * SSE events land directly in Room rather than being captured in a recording
 * buffer. The two harnesses cannot be combined because [ClientSyncDomainRegistry]
 * enforces a single handler per domain name.
 *
 * Pre-seeds two book rows (`book-a`, `book-b`) and the required library/folder
 * rows so `book_tags` FK constraints are satisfied throughout the test session.
 */
internal fun withTagSyncEngineAgainstServer(block: suspend TagSyncEngineScope.() -> Unit) {
    testApplication {
        // ---- Server side: temp-file SQLite + tag domains ----
        val tmp = Files.createTempFile("listenup-tags-e2e-", ".db").toFile().apply { deleteOnExit() }
        // DatabaseFactory.init runs the migrations; the repos read/write through a SQLDelight driver
        // opened over that same already-migrated file.
        DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val serverDriver = DriverFactory().createDriver(tmp.absolutePath)
        val serverSqlDb = ServerSqlDatabase(serverDriver)
        val bus = ChangeBus()
        val syncRegistry = SyncRegistry()

        val now = System.currentTimeMillis()
        serverDriver.execute(
            null,
            "INSERT INTO libraries(id, name, created_at, updated_at, revision) " +
                "VALUES ('test-library', 'Test Library', $now, $now, 0)",
            0,
        )
        serverDriver.execute(
            null,
            "INSERT INTO library_folders(id, library_id, root_path, created_at, updated_at, revision) " +
                "VALUES ('test-folder', 'test-library', '/tmp/test-library', $now, $now, 0)",
            0,
        )
        // Seed book rows so book_tags FK constraints are satisfied.
        serverDriver.execute(
            null,
            "INSERT INTO books(id, library_id, title, total_duration, root_rel_path, scanned_at, " +
                "revision, created_at, updated_at) VALUES " +
                "('book-a', 'test-library', 'Test Book A', 0, 'book-a', $now, 0, $now, $now)",
            0,
        )
        serverDriver.execute(
            null,
            "INSERT INTO books(id, library_id, title, total_duration, root_rel_path, scanned_at, " +
                "revision, created_at, updated_at) VALUES " +
                "('book-b', 'test-library', 'Test Book B', 0, 'book-b', $now, 0, $now, $now)",
            0,
        )

        val tagRepo = TagRepository(serverSqlDb, bus, syncRegistry)
        val bookTagRepo = BookTagRepository(serverSqlDb, bus, syncRegistry)

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(ServerSSE)
            install(Authentication) { testAuth(defaultUserId = "u1") }
            install(Koin) {
                modules(
                    module {
                        single { bus }
                        single { syncRegistry }
                        single(createdAtStart = true) { tagRepo }
                        single(createdAtStart = true) { bookTagRepo }
                    },
                )
            }
            routing {
                authenticate(JWT_PROVIDER) { syncRoutes() }
            }
        }

        // ---- Client side ----
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clientDb = createInMemoryTestDatabase()
        val testClient: HttpClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                install(SSE)
            }

        try {
            val registry = ClientSyncDomainRegistry()

            // Register the real production catalog's handlers — including the real tag
            // handlers (no recording fixture here), so SSE events on every domain land in
            // Room and none get logged as "unhandled" warnings during the test.
            registerTestSyncDomains(db = clientDb, registry = registry)

            val state = SyncEngineState()
            val store = SyncCursorStore(clientDb.syncCursorDao())
            val queue =
                PendingOperationQueue(
                    dao = clientDb.pendingOperationV2Dao(),
                    sender = DomainPendingOperationSender(emptyMap()),
                )

            val catchUp =
                SyncCatchUpClient(
                    httpClientProvider = { testClient },
                    serverUrlProvider = { "" },
                    store = store,
                    transactionRunner = RoomTransactionRunner(clientDb),
                )

            val sseClient =
                SyncSseClient(
                    serverUrlProvider = { "" },
                    streamingClientProvider = { testClient },
                    state = state,
                    scope = clientScope,
                )

            var engineRef: SyncEngine? = null
            val dispatcher =
                SyncEventDispatcher(
                    registry = registry,
                    queue = queue,
                    state = state,
                    cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                    onCursorStale = {
                        checkNotNull(engineRef) { "SyncEngine not yet constructed" }
                            .handleCursorStale()
                    },
                )

            val digestClient = DomainDigestClient(httpClientProvider = { testClient }, serverUrlProvider = { "" })
            val reconciler = SyncReconciler(registry, store, digestClient, catchUp)

            val engine =
                SyncEngine(
                    registry = registry,
                    queue = queue,
                    state = state,
                    store = store,
                    catchUp = catchUp,
                    sseClient = sseClient,
                    reconciler = reconciler,
                    dispatcher = dispatcher,
                    presenceRefreshSignal = PresenceRefreshSignal(),
                    scope = clientScope,
                )
            engineRef = engine

            try {
                TagSyncEngineScope(
                    engine = engine,
                    tagRepo = tagRepo,
                    bookTagRepo = bookTagRepo,
                    clientDatabase = clientDb,
                ).block()
            } finally {
                engine.stopAndJoin()
            }
        } finally {
            clientScope.cancel()
            clientDb.close()
            if (GlobalContext.getKoinApplicationOrNull() != null) {
                GlobalContext.stopKoin()
            }
        }
    }
}
