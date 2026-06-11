package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer

/**
 * Regression guard for the post-restore stale-Room bug.
 *
 * A server-side restore is a wholesale DB swap that is NOT announced on the sync firehose,
 * so the only way the client refreshes Room is an explicit digest reconcile. The recovery
 * path is [SyncRepositoryImpl.forceFullResync] → [SyncEngine.forceReconcile]. The trap:
 * by the time an admin triggers a restore the engine is already running, so
 * [SyncEngine.start] hits its same-user no-op guard and never reaches the reconcile step.
 *
 * Pre-fix `forceFullResync` delegated only to `start()`, so it did nothing while the engine
 * was active — restore reported success but Room stayed stale until a cold restart. This test
 * pins that [SyncEngine.forceReconcile] DOES run the digest reconcile (re-pulling a drifted
 * domain) even after the engine is already started and `start()` would no-op.
 */
class ForceReconcileWhileActiveTest :
    FunSpec({

        test("forceReconcile re-pulls a drifted domain even when the engine is already active (start would no-op)") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val catchUp = CountingFromZeroCatchUp()
                    val state = SyncEngineState()
                    val sse = FakeForceReconcileSseClient(state)

                    // The reconciler sees a stored cursor (so reconcileAll does not early-return)
                    // and a server digest that disagrees with the local rows for "tags" — i.e.
                    // the domain has drifted, exactly as a restore would leave it.
                    val max = 100L
                    val tagRows = listOf("t1" to 5L)
                    val driftedServerDigest = DigestComputer.compute(max, listOf("t1" to 999L))

                    val handler = DriftingTagHandler(rows = tagRows)
                    val registry = ClientSyncDomainRegistry()
                    registry.register(handler)
                    val store = SyncCursorStore(db.syncCursorDao())
                    store.setCursor("tags", max)

                    val reconciler =
                        SyncReconciler(
                            registry = registry,
                            store = store,
                            digestClient = fakeDigestClient(mapOf("tags" to driftedServerDigest)),
                            catchUp = catchUp,
                        )
                    val engine = buildEngine(db, registry, store, state, catchUp, sse, reconciler, scope)

                    // Start the engine, then start again: the second call hits the same-user
                    // no-op guard and never reaches start()'s own reconcile step.
                    engine.start(currentUserId = "user-a")
                    val afterStart = catchUp.fromZeroInvocations.get()
                    engine.start(currentUserId = "user-a")
                    catchUp.fromZeroInvocations.get() shouldBe afterStart

                    // The force path must still reconcile despite the no-op — this is the fix.
                    engine.forceReconcile()

                    catchUp.fromZeroInvocations.get() shouldBe afterStart + 1
                } finally {
                    scope.cancel()
                    scope.coroutineContext.job.children
                        .forEach { it.join() }
                    db.close()
                }
            }
        }
    })

private fun buildEngine(
    db: ListenUpDatabase,
    registry: ClientSyncDomainRegistry,
    store: SyncCursorStore,
    state: SyncEngineState,
    catchUp: CatchUp,
    sse: SseClient,
    reconciler: SyncReconciler,
    scope: CoroutineScope,
): SyncEngine {
    val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = PendingOperationSender { AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "t1")) },
        )
    val dispatcher =
        SyncEventDispatcher(
            registry = registry,
            queue = queue,
            state = state,
            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
        )
    return SyncEngine(
        registry = registry,
        queue = queue,
        state = state,
        store = store,
        catchUp = catchUp,
        sseClient = sse,
        reconciler = reconciler,
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        activityRefreshSignal = ActivityRefreshSignal(),
        scope = scope,
    )
}

/**
 * [DomainDigestClient] backed by an in-memory map: a GET to `/api/v1/sync/<domain>/digest`
 * responds with the pre-set [DomainDigest] for that domain, or 404 if absent.
 * Mirrors `SyncReconcilerTest`'s fake.
 */
private fun fakeDigestClient(domainDigests: Map<String, DomainDigest>): DomainDigestClient {
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
                        content = contractJson.encodeToString(DomainDigest.serializer(), digest),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(content = "not found", status = io.ktor.http.HttpStatusCode.NotFound)
                }
            },
        ) { install(ContentNegotiation) { json(contractJson) } }
    return DomainDigestClient(
        httpClientProvider = { mockClient },
        serverUrlProvider = { "http://test" },
    )
}

/**
 * Recording fake that counts `catchUpFromZero` invocations — the signal for "did the
 * reconciler detect drift and trigger a re-pull?" `catchUpAll` succeeds without counting
 * so `start()`'s own catch-up doesn't pollute the assertion.
 */
private class CountingFromZeroCatchUp : CatchUp {
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
private class DriftingTagHandler(
    private val rows: List<Pair<String, Long>>,
) : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer: KSerializer<Tag> = Tag.serializer()

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

/** Fake SSE client mirroring production's `connect()`-flips-state semantics. */
private class FakeForceReconcileSseClient(
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
