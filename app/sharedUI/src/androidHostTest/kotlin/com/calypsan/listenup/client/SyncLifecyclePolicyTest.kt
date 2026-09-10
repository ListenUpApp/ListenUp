package com.calypsan.listenup.client

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.SyncRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Tests for [SyncLifecyclePolicy] — the connect/disconnect pair that belongs to one foreground
 * window.
 *
 * The bug that motivates this: the disconnect half lived in `MainActivity.onPause`, which fires
 * for a photo picker, a permission dialog, or a multi-window focus change. Every one of those
 * tore the realtime-sync socket down and rebuilt it seconds later. Leaving the STARTED window is
 * the honest "the app went to the background" signal, so both halves live there now.
 */
class SyncLifecyclePolicyTest :
    FunSpec({

        test("connecting happens once per authenticated STARTED window") {
            runTest {
                val repository = RecordingSyncRepository()
                val authenticated = MutableStateFlow(true)
                val policy = SyncLifecyclePolicy(repository) { authenticated }

                val window = launch { policy.runWhileStarted() }
                runCurrent()

                repository.connectCount shouldBe 1

                // A repeat emission of the same value must not re-connect.
                authenticated.value = true
                runCurrent()
                repository.connectCount shouldBe 1

                window.cancelAndJoin()
            }
        }

        test("leaving the STARTED window disconnects exactly once") {
            runTest {
                val repository = RecordingSyncRepository()
                val authenticated = MutableStateFlow(true)
                val policy = SyncLifecyclePolicy(repository) { authenticated }

                val window = launch { policy.runWhileStarted() }
                runCurrent()

                repository.connectCount shouldBe 1
                repository.disconnectCount shouldBe 0

                window.cancelAndJoin()

                // The regression this pins: a plain suspend call in the finally would abort at
                // its first suspension point, because the scope is already cancelling.
                repository.disconnectCount shouldBe 1
            }
        }

        test("an unauthenticated window never connects but still disconnects on exit") {
            runTest {
                val repository = RecordingSyncRepository()
                val authenticated = MutableStateFlow(false)
                val policy = SyncLifecyclePolicy(repository) { authenticated }

                val window = launch { policy.runWhileStarted() }
                runCurrent()

                repository.connectCount shouldBe 0

                window.cancelAndJoin()

                repository.disconnectCount shouldBe 1
            }
        }
    })

/** In-memory [SyncRepository] that counts the two calls this policy is responsible for. */
private class RecordingSyncRepository : SyncRepository {
    var connectCount = 0
        private set
    var disconnectCount = 0
        private set

    override val syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
    override val isServerScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val scanProgress: StateFlow<ScanProgressState?> = MutableStateFlow(null)
    override val isBuildingInitialLibrary: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun sync(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun connectRealtime() {
        connectCount++
    }

    override suspend fun recoverRealtime(forceReconcile: Boolean) = Unit

    override suspend fun disconnect() {
        // A real suspension point, deliberately: the production disconnect suspends, and without
        // it this fake would count the call even from a `finally` that is not NonCancellable —
        // making the "disconnects exactly once on exit" test vacuous.
        yield()
        disconnectCount++
    }

    override suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun refreshListeningHistory(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun forceFullResync(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun hasLocalLibrary(): Boolean = false
}
