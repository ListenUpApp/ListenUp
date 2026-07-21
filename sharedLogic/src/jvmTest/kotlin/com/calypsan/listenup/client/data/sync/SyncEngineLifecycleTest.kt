package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.seconds

private const val FIRST_REVISION = 1L
private const val SECOND_REVISION = 2L

// "tags" is not a real outbox channel — a minimal local fixture for a hypothetical
// un-mirrored domain, matching the queue's payload-agnostic contract.
private val tagsChannel = OutboxChannel("tags", String.serializer(), setOf(OpKind.Upsert), idempotent = true)
private const val FIRST_UPDATED_AT = 100L
private const val SECOND_UPDATED_AT = 200L

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineLifecycleTest :
    FunSpec({

        test("start runs catch-up THEN connects SSE — never overlapping") {
            runTest {
                val sequence = mutableListOf<String>()
                val handler =
                    object : SyncDomainHandler<Tag> {
                        override val domainName = "tags"
                        override val payloadSerializer = Tag.serializer()

                        override fun syncId(item: Tag): String = item.id

                        override suspend fun onEvent(
                            event: SyncEvent<Tag>,
                        ): AppResult<Unit> {
                            sequence += "sse:${event.id}"
                            return AppResult.Success(Unit)
                        }

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> {
                            sequence += "catchup:${item.id}"
                            return AppResult.Success(Unit)
                        }

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
                    }
                val registry = ClientSyncDomainRegistry()
                registry.register(handler)
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val items =
                    listOf(
                        Tag(id = "a", name = "x", slug = "x", revision = FIRST_REVISION, updatedAt = FIRST_UPDATED_AT),
                        Tag(id = "b", name = "y", slug = "y", revision = SECOND_REVISION, updatedAt = SECOND_UPDATED_AT),
                    )
                val fakeCatchUp = FakeCatchUp(items = items, store = store)
                val fakeSse = FakeSse()
                val state = SyncEngineState()
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
                        catchUp = fakeCatchUp,
                        sseClient = fakeSse,
                        reconciler = noopSyncReconciler(registry, store, fakeCatchUp),
                        dispatcher = dispatcher,
                        presenceRefreshSignal = PresenceRefreshSignal(),
                        scope = backgroundScope,
                    )

                engine.start(currentUserId = "u1")

                sequence shouldContainExactly listOf("catchup:a", "catchup:b")
                fakeSse.connected shouldBe true
                fakeSse.seededLastEventId shouldBe SECOND_REVISION

                engine.stopAndJoin()
                db.close()
            }
        }

        test("start clears the queue when currentUserId differs from queued ops' owner") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val u1opId = queue.enqueue(tagsChannel, "t1", OpKind.Upsert, "{}", "u1")
                val state = SyncEngineState()
                val fakeCatchUp2 = FakeCatchUp(emptyList(), store)
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = fakeCatchUp2,
                        sseClient = FakeSse(),
                        reconciler = noopSyncReconciler(registry, store, fakeCatchUp2),
                        dispatcher =
                            SyncEventDispatcher(
                                registry = registry,
                                state = state,
                                cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                            ),
                        presenceRefreshSignal = PresenceRefreshSignal(),
                        scope = backgroundScope,
                    )
                engine.start(currentUserId = "u2")

                db.pendingOperationV2Dao().get(u1opId) shouldBe null

                engine.stopAndJoin()
                db.close()
            }
        }

        test("start wires SSE frames into the dispatcher before connecting") {
            runTest {
                val sequence = mutableListOf<String>()
                val handler =
                    object : SyncDomainHandler<Tag> {
                        override val domainName = "tags"
                        override val payloadSerializer = Tag.serializer()

                        override fun syncId(item: Tag): String = item.id

                        override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> {
                            sequence += "sse:${event.id}"
                            return AppResult.Success(Unit)
                        }

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
                    }
                val registry = ClientSyncDomainRegistry()
                registry.register(handler)
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val state = SyncEngineState()
                val fakeSse = FakeSse()
                val fakeCatchUp3 = FakeCatchUp(emptyList(), store)
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = fakeCatchUp3,
                        sseClient = fakeSse,
                        reconciler = noopSyncReconciler(registry, store, fakeCatchUp3),
                        dispatcher =
                            SyncEventDispatcher(
                                registry = registry,
                                state = state,
                                cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                            ),
                        presenceRefreshSignal = PresenceRefreshSignal(),
                        scope = backgroundScope,
                    )

                engine.start(currentUserId = "u1")
                fakeSse.awaitCollector()
                fakeSse.emit(
                    SyncFrame(
                        revision = 3L,
                        domain = "tags",
                        json =
                            contractJson.encodeToString(
                                SyncEvent.serializer(Tag.serializer()),
                                SyncEvent.Created(
                                    id = "t3",
                                    revision = 3L,
                                    occurredAt = 300L,
                                    clientOpId = null,
                                    payload = Tag(id = "t3", name = "z", slug = "z", revision = 3L, updatedAt = 300L),
                                ),
                            ),
                    ),
                )
                advanceUntilIdle()

                sequence shouldContainExactly listOf("sse:t3")
                store.highestCursor() shouldBe 3L

                engine.stopAndJoin()
                db.close()
            }
        }

        test("stopAndJoin waits for in-flight frame dispatch before returning") {
            runTest {
                val dispatchStarted = CompletableDeferred<Unit>()
                val dispatchFinished = CompletableDeferred<Unit>()
                val registry = ClientSyncDomainRegistry()
                registry.register(
                    object : SyncDomainHandler<Tag> {
                        override val domainName = "tags"
                        override val payloadSerializer = Tag.serializer()

                        override fun syncId(item: Tag): String = item.id

                        override suspend fun onEvent(
                            event: SyncEvent<Tag>,
                        ): AppResult<Unit> {
                            dispatchStarted.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                            dispatchFinished.complete(Unit)
                            return AppResult.Success(Unit)
                        }

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
                    },
                )
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val state = SyncEngineState()
                val fakeSse = FakeSse()
                val fakeCatchUp4 = FakeCatchUp(emptyList(), store)
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = fakeCatchUp4,
                        sseClient = fakeSse,
                        reconciler = noopSyncReconciler(registry, store, fakeCatchUp4),
                        dispatcher =
                            SyncEventDispatcher(
                                registry = registry,
                                state = state,
                                cursorAdvance = { _, _ -> },
                            ),
                        presenceRefreshSignal = PresenceRefreshSignal(),
                        scope = backgroundScope,
                    )

                engine.start(currentUserId = "u1")
                fakeSse.awaitCollector()
                val emitJob =
                    backgroundScope.launch {
                        fakeSse.emit(
                            SyncFrame(
                                revision = 1L,
                                domain = "tags",
                                json =
                                    contractJson.encodeToString(
                                        SyncEvent.serializer(Tag.serializer()),
                                        SyncEvent.Created(
                                            id = "t1",
                                            revision = 1L,
                                            occurredAt = 100L,
                                            clientOpId = null,
                                            payload = Tag(id = "t1", name = "alpha", slug = "alpha", revision = 1L, updatedAt = 100L),
                                        ),
                                    ),
                            ),
                        )
                    }
                dispatchStarted.await()

                withTimeout(1.seconds) { engine.stopAndJoin() }
                dispatchFinished.isCompleted shouldBe false
                emitJob.cancel()
                emitJob.join()
                db.close()
            }
        }
    })

// Local fakes for lifecycle test only.
private class FakeCatchUp(
    private val items: List<Tag>,
    private val store: SyncCursorStore,
) : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> {
        // Tag-only — works because tests register only Tag handlers.
        @Suppress("UNCHECKED_CAST")
        val tagHandler = handler as SyncDomainHandler<Tag>
        for (item in items) {
            tagHandler.onCatchUpItem(item, isTombstone = item.deletedAt != null)
            store.setCursor(handler.domainName, item.revision)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = catchUp(handler)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        for (name in registry.registeredDomains()) {
            val h = registry.lookup(name) ?: continue

            @Suppress("UNCHECKED_CAST")
            catchUp(h as SyncDomainHandler<Any>)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

private class FakeSse : SyncStreamClient {
    private val flow = MutableSharedFlow<SyncFrame>()
    override val frames: SharedFlow<SyncFrame> = flow.asSharedFlow()
    var connected = false
    var seededLastEventId: Long? = null

    override fun seedLastEventId(initial: Long?) {
        seededLastEventId = initial
    }

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        connected = false
    }

    override fun currentLastEventId(): Long? = seededLastEventId

    override suspend fun reseed(newLastEventId: Long?) {
        disconnect()
        seededLastEventId = newLastEventId
    }

    override fun reconnectNow() = Unit

    suspend fun emit(frame: SyncFrame) {
        flow.emit(frame)
    }

    suspend fun awaitCollector() {
        flow.subscriptionCount.first { it > 0 }
    }
}
