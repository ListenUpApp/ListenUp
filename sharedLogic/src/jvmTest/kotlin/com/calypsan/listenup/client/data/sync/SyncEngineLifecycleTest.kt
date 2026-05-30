package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

private const val FIRST_REVISION = 1L
private const val SECOND_REVISION = 2L
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
                            isOwnEcho: Boolean,
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
                    }
                val registry = ClientSyncDomainRegistry()
                registry.register(handler)
                val db = createInMemoryTestDatabase()
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
                        catchUp = fakeCatchUp,
                        sseClient = fakeSse,
                        dispatcher = dispatcher,
                        downloadRepository = FakeDownloadRepository(),
                        scope = backgroundScope,
                    )

                engine.start(currentUserId = "u1")

                sequence shouldContainExactly listOf("catchup:a", "catchup:b")
                fakeSse.connected shouldBe true
                fakeSse.seededLastEventId shouldBe SECOND_REVISION

                db.close()
            }
        }

        test("start clears the queue when currentUserId differs from queued ops' owner") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                val db = createInMemoryTestDatabase()
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val u1opId = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                val state = SyncEngineState()
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = FakeCatchUp(emptyList(), store),
                        sseClient = FakeSse(),
                        dispatcher =
                            SyncEventDispatcher(
                                registry = registry,
                                queue = queue,
                                state = state,
                                cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                            ),
                        downloadRepository = FakeDownloadRepository(),
                        scope = backgroundScope,
                    )
                engine.start(currentUserId = "u2")

                db.pendingOperationV2Dao().get(u1opId) shouldBe null

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

                        override suspend fun onEvent(
                            event: SyncEvent<Tag>,
                            isOwnEcho: Boolean,
                        ): AppResult<Unit> {
                            sequence += "sse:${event.id}:$isOwnEcho"
                            return AppResult.Success(Unit)
                        }

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)
                    }
                val registry = ClientSyncDomainRegistry()
                registry.register(handler)
                val db = createInMemoryTestDatabase()
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val state = SyncEngineState()
                val fakeSse = FakeSse()
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = FakeCatchUp(emptyList(), store),
                        sseClient = fakeSse,
                        dispatcher =
                            SyncEventDispatcher(
                                registry = registry,
                                queue = queue,
                                state = state,
                                cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                            ),
                        downloadRepository = FakeDownloadRepository(),
                        scope = backgroundScope,
                    )

                engine.start(currentUserId = "u1")
                fakeSse.awaitCollector()
                fakeSse.emit(
                    ParsedSseFrame(
                        id = 3L,
                        event = "tags",
                        data =
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

                sequence shouldContainExactly listOf("sse:t3:false")
                store.highestCursor() shouldBe 3L

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
                            isOwnEcho: Boolean,
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
                    },
                )
                val db = createInMemoryTestDatabase()
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val state = SyncEngineState()
                val fakeSse = FakeSse()
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = FakeCatchUp(emptyList(), store),
                        sseClient = fakeSse,
                        dispatcher =
                            SyncEventDispatcher(
                                registry = registry,
                                queue = queue,
                                state = state,
                                cursorAdvance = { _, _ -> },
                            ),
                        downloadRepository = FakeDownloadRepository(),
                        scope = backgroundScope,
                    )

                engine.start(currentUserId = "u1")
                fakeSse.awaitCollector()
                val emitJob =
                    backgroundScope.launch {
                        fakeSse.emit(
                            ParsedSseFrame(
                                id = 1L,
                                event = "tags",
                                data =
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

private class FakeSse : SseClient {
    private val flow = MutableSharedFlow<ParsedSseFrame>()
    override val frames: SharedFlow<ParsedSseFrame> = flow.asSharedFlow()
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

    suspend fun emit(frame: ParsedSseFrame) {
        flow.emit(frame)
    }

    suspend fun awaitCollector() {
        flow.subscriptionCount.first { it > 0 }
    }
}
