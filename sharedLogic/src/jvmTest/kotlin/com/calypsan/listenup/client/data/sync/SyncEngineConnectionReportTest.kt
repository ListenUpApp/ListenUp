@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** CatchUp whose pass-level catchUpAll always fails with a typed auth error. */
private class FailingCatchUp : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Failure(AuthError.SessionExpired())

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

class SyncEngineConnectionReportTest :
    FunSpec({
        test("lifecycle-reconcile and cursor-stale catch-up failures are forwarded typed, and the passes still complete") {
            runTest {
                val registry = ClientSyncDomainRegistry() // empty: digest reconcile is a no-op
                val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
                val store = SyncCursorStore(db.syncCursorDao())
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val state = SyncEngineState()
                val failingCatchUp = FailingCatchUp()
                val reported = mutableListOf<AppError>()
                val engine =
                    SyncEngine(
                        registry = registry,
                        queue = queue,
                        state = state,
                        store = store,
                        catchUp = failingCatchUp,
                        sseClient = FakeReportSse(),
                        reconciler = noopSyncReconciler(registry, store, failingCatchUp),
                        dispatcher =
                            SyncEventDispatcher(
                                registry = registry,
                                state = state,
                                cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                            ),
                        presenceRefreshSignal = PresenceRefreshSignal(),
                        scope = backgroundScope,
                        reportConnectionIssue = { reported += it },
                    )

                engine.lifecycleReconcile(force = true) // SyncEngine.kt:444-451 site
                engine.handleCursorStale() // SyncEngine.kt:554-561 site

                reported.map { it.code } shouldContainExactly
                    listOf("AUTH_SESSION_EXPIRED", "AUTH_SESSION_EXPIRED")

                db.close()
            }
        }
    })

/** Minimal SseClient for the report test — no frames, no state writes. */
private class FakeReportSse : SseClient {
    private val flow = kotlinx.coroutines.flow.MutableSharedFlow<ParsedSseFrame>()
    override val frames: kotlinx.coroutines.flow.SharedFlow<ParsedSseFrame> = flow

    override fun seedLastEventId(initial: Long?) = Unit

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun currentLastEventId(): Long? = null

    override suspend fun reseed(newLastEventId: Long?) = Unit

    override fun reconnectNow() = Unit
}
