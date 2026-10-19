package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Control-frame callbacks run INSIDE the engine's frame collector. A callback that stops the engine
 * (as the full local sign-out on an account deletion does) would make the collector cancel and join
 * itself, so the work after the stop never runs. This is why the `onUserDeleted` wiring launches the
 * sign-out on the app scope instead of calling it inline.
 */
class UserDeletedStopsEngineInlineTest :
    FunSpec({

        test("a control-frame callback that stops the engine inline is cancelled before it finishes") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()
                    val store = SyncCursorStore(db.syncCursorDao())
                    val state = SyncEngineState()
                    val catchUp = SucceedingCatchUp()
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                        )
                    val frames = MutableSharedFlow<SyncFrame>()
                    lateinit var engine: SyncEngine
                    val started = CompletableDeferred<Unit>()
                    var finished = false
                    val dispatcher =
                        SyncEventDispatcher(
                            registry = registry,
                            state = state,
                            cursorAdvance = liveFrameCursorAdvance(store),
                            onUserDeleted = {
                                started.complete(Unit)
                                engine.stopAndJoin()
                                finished = true
                            },
                        )
                    engine =
                        SyncEngine(
                            registry = registry,
                            queue = queue,
                            state = state,
                            store = store,
                            catchUp = catchUp,
                            syncStreamClient = FramesOnlyStreamClient(state, frames),
                            reconciler = noopSyncReconciler(registry, store, catchUp),
                            dispatcher = dispatcher,
                            presenceRefreshSignal = PresenceRefreshSignal(),
                            scope = scope,
                        )

                    engine.start(currentUserId = "u1")
                    withTimeout(5.seconds) { frames.subscriptionCount.first { it > 0 } }
                    frames.emit(
                        SyncFrame(
                            revision = null,
                            domain = "control",
                            json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.UserDeleted("x")),
                        ),
                    )
                    withTimeout(5.seconds) { started.await() }
                    delay(300)

                    finished shouldBe false
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }
    })

private class SucceedingCatchUp : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>) = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>) = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>) = AppResult.Success(emptySet<String>())

    override suspend fun domains() = AppResult.Success(emptyList<String>())
}

private class FramesOnlyStreamClient(
    private val state: SyncEngineState,
    flow: MutableSharedFlow<SyncFrame>,
) : SyncStreamClient {
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
        seeded = newLastEventId
    }

    override fun reconnectNow() = Unit
}
