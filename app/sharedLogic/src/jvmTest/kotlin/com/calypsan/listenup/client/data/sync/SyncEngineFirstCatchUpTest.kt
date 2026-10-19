package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A device that has never pulled shows "first sync" while its first catch-up runs (Home §2.2 row 1); a device with
 * cursors, or a catch-up that has ended either way, does not.
 */
class SyncEngineFirstCatchUpTest :
    FunSpec({
        test("a store with no cursors marks the catch-up as the first while it runs, and clears it after") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val gate = CompletableDeferred<Unit>()
                    val catchUp = FirstPullGatedCatchUp(gate)
                    val state = SyncEngineState()
                    val engine =
                        buildStartTestEngine(db, state, catchUp, FakeIdempotencySyncStreamClient(state), scope)

                    val started = scope.launch { engine.start(currentUserId = "user-a") }
                    catchUp.entered.await()
                    state.value.isFirstCatchUp shouldBe true

                    gate.complete(Unit)
                    started.join()
                    state.value.isFirstCatchUp shouldBe false
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }

        test("a store that already holds a cursor never marks a first catch-up") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    SyncCursorStore(db.syncCursorDao()).setCursor(domainName = "tags", revision = 42L)
                    val gate = CompletableDeferred<Unit>()
                    val catchUp = FirstPullGatedCatchUp(gate)
                    val state = SyncEngineState()
                    val engine =
                        buildStartTestEngine(db, state, catchUp, FakeIdempotencySyncStreamClient(state), scope)

                    val started = scope.launch { engine.start(currentUserId = "user-a") }
                    catchUp.entered.await()
                    state.value.isFirstCatchUp shouldBe false
                    gate.complete(Unit)
                    started.join()
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }

        test("a first catch-up that fails still clears the mark, so no skeleton waits forever") {
            runBlocking {
                // The throw ends the engine's own job; the handler keeps it from being reported as uncaught.
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
                val db = createInMemoryTestDatabase()
                try {
                    val state = SyncEngineState()
                    val engine =
                        buildStartTestEngine(db, state, FirstPullThrowingCatchUp(), FakeIdempotencySyncStreamClient(state), scope)
                    engine.start(currentUserId = "user-a")
                    state.value.isFirstCatchUp shouldBe false
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }

        test("a first catch-up that answers a typed failure clears the mark too") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val db = createInMemoryTestDatabase()
                try {
                    val state = SyncEngineState()
                    val engine =
                        buildStartTestEngine(db, state, FirstPullFailingCatchUp(), FakeIdempotencySyncStreamClient(state), scope)
                    engine.start(currentUserId = "user-a")
                    state.value.isFirstCatchUp shouldBe false
                } finally {
                    scope.cancel()
                    db.close()
                }
            }
        }
    })

/** Suspends in catchUpAll until [gate] completes; [entered] says it got there. */
private class FirstPullGatedCatchUp(
    private val gate: CompletableDeferred<Unit>,
) : CountingCatchUp() {
    val entered = CompletableDeferred<Unit>()

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> {
        entered.complete(Unit)
        gate.await()
        return AppResult.Success(Unit)
    }
}

/** catchUpAll answers a typed failure, as a dropped connection does. */
private class FirstPullFailingCatchUp : CountingCatchUp() {
    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> =
        AppResult.Failure(SyncError.SyncFailed(debugInfo = "test"))
}

/** catchUpAll throws, as an unexpected database error does. */
private class FirstPullThrowingCatchUp : CountingCatchUp() {
    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = error("simulated")
}
