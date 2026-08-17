package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.data.sync.domains.preferencesDomain
import com.calypsan.listenup.client.data.sync.domains.presenceDomain
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

        test("start primes the refreshed tier once, and a reconnect re-fires it") {
            runBlocking {
                val scope =
                    CoroutineScope(
                        SupervisorJob() + Dispatchers.Default,
                    )
                val db = createInMemoryTestDatabase()
                try {
                    val presencePings = AtomicInteger(0)
                    // The user's synced playback defaults ride this refetch. Nothing else pulls
                    // them — not catch-up, not the digest — so if start doesn't prime them, a book
                    // opened right after a fresh sign-in plays at the stock 1x, not the user's 2x.
                    val preferenceRefetches = AtomicInteger(0)
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
                            syncStreamClient = sse,
                            reconciler = reconciler,
                            dispatcher = dispatcher,
                            presenceRefreshSignal = presence,
                            scope = scope,
                            // The lifecycle-reconcile pass re-runs every refreshed domain's refresh via
                            // the router: presence's refresh pings on the reconnect edge. Supplying the
                            // presence domain (whose ping targets this signal) is what makes the reconnect
                            // edge re-fire the ping.
                            refreshedRouter =
                                RefreshedDomainRouter(
                                    listOf(
                                        presenceDomain(ping = { presence.ping() }),
                                        preferencesDomain(
                                            refetch = { preferenceRefetches.incrementAndGet() },
                                        ),
                                    ),
                                ),
                        )

                    engine.start(currentUserId = "u1")

                    // After start: start's own reconcile re-pulled once (drifted), and start primes
                    // the refreshed tier — the domains no catch-up or digest covers, which would
                    // otherwise sit empty on a cold start with no trigger coming.
                    catchUp.fromZeroInvocations.get() shouldBe 1
                    // Let the reconnect observer settle on (and drop) the initial Connected — the
                    // prime is start's, so the connect edge must not add a second ping.
                    delay(150)
                    presencePings.get() shouldBe 1
                    preferenceRefetches.get() shouldBe 1

                    // Drive a real reconnect edge: drop, let the observer see Disconnected, then connect.
                    sse.disconnect()
                    delay(150)
                    sse.connect()

                    // The reconnect fires both actions exactly once more.
                    withTimeout(5_000L) {
                        while (presencePings.get() < 2 ||
                            catchUp.fromZeroInvocations.get() < 2
                        ) {
                            delay(10)
                        }
                    }
                    presencePings.get() shouldBe 2
                    preferenceRefetches.get() shouldBe 2
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
) : SyncStreamClient {
    private val flow = MutableSharedFlow<SyncFrame>()
    override val frames: SharedFlow<SyncFrame> = flow.asSharedFlow()
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
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = rows
}

/**
 * Digest client backed by an in-memory map: a known domain yields its preset [DomainDigest], an
 * unknown one the typed [SyncError.UnknownDomain] the server would return.
 */
private fun fakeRefreshDigestClient(domainDigests: Map<String, DomainDigest>): DomainDigestClient {
    val service =
        object : FakeSyncStreamService() {
            override suspend fun digest(
                domain: String,
                cursor: Long,
            ): AppResult<DomainDigest> =
                domainDigests[domain]
                    ?.let { AppResult.Success(it) }
                    ?: AppResult.Failure(SyncError.UnknownDomain(domain = domain))
        }
    return DomainDigestClient(channel = RpcChannel.forTest(service))
}
