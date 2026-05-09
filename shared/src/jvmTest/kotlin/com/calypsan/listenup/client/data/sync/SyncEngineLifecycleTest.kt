package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest

private const val FIRST_REVISION = 1L
private const val SECOND_REVISION = 2L
private const val FIRST_UPDATED_AT = 100L
private const val SECOND_UPDATED_AT = 200L

class SyncEngineLifecycleTest :
    FunSpec({

        test("start runs catch-up THEN connects SSE — never overlapping") {
            runTest {
                val sequence = mutableListOf<String>()
                val handler =
                    object : SyncDomainHandler<Tag> {
                        override val domainName = "tags"
                        override val payloadSerializer = Tag.serializer()

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
                        Tag(id = "a", name = "x", revision = FIRST_REVISION, updatedAt = FIRST_UPDATED_AT),
                        Tag(id = "b", name = "y", revision = SECOND_REVISION, updatedAt = SECOND_UPDATED_AT),
                    )
                val fakeCatchUp = FakeCatchUp(items = items, store = store)
                val fakeSse = FakeSse()

                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = SyncEngineState(),
                        store = store,
                        catchUp = fakeCatchUp,
                        sseClient = fakeSse,
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
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = SyncEngineState(),
                        store = store,
                        catchUp = FakeCatchUp(emptyList(), store),
                        sseClient = FakeSse(),
                    )
                engine.start(currentUserId = "u2")

                db.pendingOperationV2Dao().get(u1opId) shouldBe null

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
}
