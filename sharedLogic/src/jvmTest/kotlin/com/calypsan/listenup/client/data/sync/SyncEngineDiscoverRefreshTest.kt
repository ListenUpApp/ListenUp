package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SyncEngineDiscoverRefreshTest :
    FunSpec({

        test("a reconnect (not the initial connect) pings presence and reconciles") {
            runBlocking {
                val scope =
                    CoroutineScope(
                        SupervisorJob() + Dispatchers.Default,
                    )
                val db = createInMemoryTestDatabase()
                try {
                    val presencePings = AtomicInteger(0)
                    val catchUp = CountingReconcileCatchUp()
                    val state = SyncEngineState()
                    val sse = FlippingFakeSse(state)

                    // Reconciler sees a stored cursor (so reconcileAll does not early-return) and a
                    // drifted server digest for "tags" — so every reconcileAll re-pulls (fromZero++).
                    val max = 100L
                    val driftedServerDigest = DigestComputer.compute(max, listOf("t1" to 999L))
                    val handler = DriftingRefreshHandler(rows = listOf("t1" to 5L))
                    val registry = ClientSyncDomainRegistry()
                    registry.register(handler)
                    val store = SyncCursorStore(db.syncCursorDao())
                    store.setCursor("tags", max)
                    val reconciler =
                        SyncReconciler(
                            registry = registry,
                            store = store,
                            digestClient = fakeRefreshDigestClient(mapOf("tags" to driftedServerDigest)),
                            catchUp = catchUp,
                        )

                    val presence = PresenceRefreshSignal()
                    scope.launch { presence.signal.collect { presencePings.incrementAndGet() } }

                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                        )
                    val dispatcher =
                        SyncEventDispatcher(
                            registry = registry,
                            queue = queue,
                            state = state,
                            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                        )
                    val engine =
                        SyncEngine(
                            registry = registry,
                            queue = queue,
                            state = state,
                            store = store,
                            catchUp = catchUp,
                            sseClient = sse,
                            reconciler = reconciler,
                            dispatcher = dispatcher,
                            presenceRefreshSignal = presence,
                            scope = scope,
                            // The lifecycle-reconcile refresh runs each nudge domain's declared
                            // recovery: presence pings on the reconnect edge. Supplying the presence
                            // domain is what makes the reconnect edge re-fire the ping.
                            refreshedDomains =
                                listOf(
                                    com.calypsan.listenup.client.data.sync.domains
                                        .presenceDomain(ping = {}),
                                ),
                        )

                    engine.start(currentUserId = "u1")

                    // After start: start's own reconcile re-pulled once (drifted); the initial connect
                    // was DROPPED so presence was not pinged.
                    catchUp.fromZeroInvocations.get() shouldBe 1
                    // Let the reconnect observer settle on (and drop) the initial Connected.
                    delay(150)
                    presencePings.get() shouldBe 0

                    // Drive a real reconnect edge: drop, let the observer see Disconnected, then connect.
                    sse.disconnect()
                    delay(150)
                    sse.connect()

                    // The reconnect fires both actions exactly once more.
                    withTimeout(5_000L) {
                        while (presencePings.get() < 1 ||
                            catchUp.fromZeroInvocations.get() < 2
                        ) {
                            delay(10)
                        }
                    }
                    presencePings.get() shouldBe 1
                    catchUp.fromZeroInvocations.get() shouldBe 2
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })

/** Fake SSE that flips [SyncEngineState] on connect/disconnect, mirroring production semantics. */
private class FlippingFakeSse(
    private val state: SyncEngineState,
) : SseClient {
    private val flow = MutableSharedFlow<ParsedSseFrame>()
    override val frames: SharedFlow<ParsedSseFrame> = flow.asSharedFlow()
    private var seeded: Long? = null

    override fun seedLastEventId(initial: Long?) {
        seeded = initial
    }

    override fun connect() {
        state.setConnection(ConnectionState.Connected(lastEventId = seeded))
    }

    override fun disconnect() {
        state.setConnection(ConnectionState.Disconnected(reason = "test"))
    }

    override fun currentLastEventId(): Long? = seeded

    override suspend fun reseed(newLastEventId: Long?) {
        disconnect()
        seeded = newLastEventId
    }

    override fun reconnectNow() = Unit
}

/**
 * Counts `catchUpFromZero` (the re-pull the reconciler triggers on drift) and `catchUpAll`
 * (start's forward drain), so a reconcile-driven re-pull is observable independently of start.
 */
private class CountingReconcileCatchUp : CatchUp {
    val fromZeroInvocations = AtomicInteger(0)

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> {
        fromZeroInvocations.incrementAndGet()
        return AppResult.Success(Unit)
    }

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/** Minimal handler whose [localDigestRows] returns fixed rows so the reconciler can compare digests. */
private class DriftingRefreshHandler(
    private val rows: List<Pair<String, Long>>,
) : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    override suspend fun onEvent(
        event: SyncEvent<Tag>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = rows
}

/** Digest client backed by an in-memory map: GET `/api/v1/sync/<domain>/digest` → preset digest or 404. */
private fun fakeRefreshDigestClient(
    domainDigests: Map<String, DomainDigest>,
): DomainDigestClient {
    val mockClient =
        HttpClient(
            MockEngine { req ->
                val domain =
                    req.url.pathSegments
                        .dropLast(1)
                        .last()
                val digest = domainDigests[domain]
                if (digest != null) {
                    respond(
                        content =
                            contractJson.encodeToString(
                                DomainDigest.serializer(),
                                digest,
                            ),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = "not found",
                        status = HttpStatusCode.NotFound,
                    )
                }
            },
        ) {
            install(ContentNegotiation) {
                json(contractJson)
            }
        }
    return DomainDigestClient(
        httpClientProvider = { mockClient },
        serverUrlProvider = { "http://test" },
    )
}
