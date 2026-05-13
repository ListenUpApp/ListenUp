package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncSseClient
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.syncRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
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
 * Test scope exposing engine internals for assertions in Tier 3 e2e tests.
 *
 * @property engine the wired client engine; tests call `engine.start(userId)`
 *   themselves so the catch-up → SSE-connect order is observable
 * @property recording the test handler observing tag events for assertions
 * @property tagRepo the server-side tag repository for triggering writes
 *   server-side
 * @property state observable engine state for ambient assertions
 * @property dispatcher the dispatcher routing SSE frames to handlers
 * @property queue the pending-operation queue for echo-match scenarios
 */
data class ClientEngineScope(
    val engine: SyncEngine,
    val recording: RecordingTagSyncDomainHandler,
    val tagRepo: TagRepository,
    val state: SyncEngineState,
    val dispatcher: SyncEventDispatcher,
    val queue: PendingOperationQueue,
    val sseClient: SyncSseClient,
)

/**
 * Boots `:server`'s test application AND the client engine in one process,
 * with a real in-memory Room DB on the client side. Use for Tier 3 e2e tests.
 *
 * Wires:
 *  - server: temp-file SQLite + Koin module + `syncRoutes`, mirroring
 *    `server/.../testing/SyncTestApplication.kt`
 *  - client: Room in-memory DB + a real [SyncEngine] backed by [SyncCatchUpClient]
 *    and [SyncSseClient] talking to the server's testApplication via the
 *    `createClient { }` JSON+SSE client (relative URLs route in-process)
 *  - frame collection: a dedicated coroutine pipes `sseClient.frames` through
 *    [SyncEventDispatcher.handle] — production does this in the Koin module;
 *    the fixture does it inline so handler observation works end-to-end.
 *
 * Stops Koin in `finally` so subsequent tests start with a fresh container.
 */
fun withClientSyncEngineAgainstServer(block: suspend ClientEngineScope.() -> Unit) {
    testApplication {
        // ---- Server side: in-memory SQLite + Tag domain registered ----
        val tmp =
            Files.createTempFile("listenup-c3-", ".db").toFile().apply { deleteOnExit() }
        val serverDb =
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val tagRepo = TagRepository(serverDb, bus, registry)

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(ServerSSE)
            install(Koin) {
                modules(
                    module {
                        single { serverDb }
                        single { bus }
                        single { registry }
                        single(createdAtStart = true) { tagRepo }
                    },
                )
            }
            routing { syncRoutes() }
        }

        // ---- Client-side test scope ----
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clientDb = createInMemoryTestDatabase()
        val testClient: HttpClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                install(SSE)
            }

        try {
            val registry = ClientSyncDomainRegistry()
            val recording = RecordingTagSyncDomainHandler(registry)
            val state = SyncEngineState()
            val store = SyncCursorStore(clientDb.syncCursorDao())
            val queue =
                PendingOperationQueue(
                    dao = clientDb.pendingOperationV2Dao(),
                    sender = PendingOperationSender { AppResult.Success(Unit) },
                )

            // testApplication routes relative URLs in-process — empty baseUrl is correct.
            val catchUp =
                SyncCatchUpClient(
                    httpClientProvider = { testClient },
                    serverUrlProvider = { "" },
                    store = store,
                )

            val sseClient =
                SyncSseClient(
                    serverUrlProvider = { "" },
                    streamingClientProvider = { testClient },
                    state = state,
                    scope = clientScope,
                )

            // Forward reference: dispatcher's onCursorStale callback needs to
            // call engine.handleCursorStale, but the engine takes the dispatcher
            // as a constructor dep. Production uses Koin's lazy `get<SyncEngine>()`
            // for this; here a single-slot holder serves the same role.
            var engineRef: SyncEngine? = null
            val dispatcher =
                SyncEventDispatcher(
                    registry = registry,
                    queue = queue,
                    state = state,
                    cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                    onCursorStale = { lastKnown ->
                        checkNotNull(engineRef) { "SyncEngine not yet constructed" }
                            .handleCursorStale(lastKnown)
                    },
                )

            val engine =
                SyncEngine(
                    registry = registry,
                    queue = queue,
                    state = state,
                    store = store,
                    catchUp = catchUp,
                    sseClient = sseClient,
                    dispatcher = dispatcher,
                    scope = clientScope,
                )
            engineRef = engine

            try {
                ClientEngineScope(
                    engine = engine,
                    recording = recording,
                    tagRepo = tagRepo,
                    state = state,
                    dispatcher = dispatcher,
                    queue = queue,
                    sseClient = sseClient,
                ).block()
            } finally {
                engine.stopAndJoin()
            }
        } finally {
            clientScope.cancel()
            clientDb.close()
            // Stop Koin so the next test starts fresh. Each test creates its own
            // `SyncRegistry` instance (passed into the per-test `TagRepository`),
            // so there's no process-wide state to clean up — `GlobalContext` is
            // only stopped because Ktor's Koin plugin starts it eagerly.
            if (GlobalContext.getKoinApplicationOrNull() != null) {
                GlobalContext.stopKoin()
            }
        }
    }
}
