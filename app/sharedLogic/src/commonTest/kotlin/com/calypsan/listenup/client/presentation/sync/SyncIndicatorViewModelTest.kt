package com.calypsan.listenup.client.presentation.sync

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.PendingOperation
import com.calypsan.listenup.client.domain.model.PendingOperationStatus
import com.calypsan.listenup.client.domain.model.PendingOperationType
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.PendingOperationRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Hand-written fake over [PendingOperationRepository] — a state-carrying test double per the
 * repo's fake-with-state convention, rather than a mock, since the tests drive multiple flows
 * plus retry/dismiss recording.
 */
private class FakePendingOperationRepository : PendingOperationRepository {
    val visible = MutableStateFlow<List<PendingOperation>>(emptyList())
    val failed = MutableStateFlow<List<PendingOperation>>(emptyList())
    val retried = mutableListOf<String>()
    val dismissed = mutableListOf<String>()

    override fun observeVisibleOperations(): Flow<List<PendingOperation>> = visible

    override fun observeInProgressOperation(): Flow<PendingOperation?> = flowOf(null)

    override fun observeFailedOperations(): Flow<List<PendingOperation>> = failed

    override suspend fun retry(id: String) {
        retried += id
    }

    override suspend fun dismiss(id: String) {
        dismissed += id
    }
}

private fun pendingOp(
    id: String,
    entityId: String = "entity1",
): PendingOperation =
    PendingOperation(
        id = id,
        operationType = PendingOperationType.BOOK_UPDATE,
        entityId = entityId,
        status = PendingOperationStatus.PENDING,
        lastError = null,
    )

private fun failedOp(
    id: String,
    entityId: String,
    lastError: String,
): PendingOperation =
    PendingOperation(
        id = id,
        operationType = PendingOperationType.BOOK_UPDATE,
        entityId = entityId,
        status = PendingOperationStatus.FAILED,
        lastError = lastError,
    )

/**
 * Characterization tests for [SyncIndicatorViewModel] against the now-real repository contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncIndicatorViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun buildViewModel(repo: FakePendingOperationRepository): SyncIndicatorViewModel {
            val syncRepository =
                mock<SyncRepository> {
                    every { syncState } returns MutableStateFlow<SyncState>(SyncState.Idle)
                }
            return SyncIndicatorViewModel(pendingOperationRepository = repo, syncRepository = syncRepository)
        }

        test("state derives pendingCount from visible PENDING operations") {
            runTest(testDispatcher) {
                val repo = FakePendingOperationRepository()
                repo.visible.value = listOf(pendingOp("op1"), pendingOp("op2"))
                val vm = buildViewModel(repo)

                vm.state.test {
                    var item = awaitItem()
                    while (item.pendingCount == 0) item = awaitItem()
                    val expectedCount = 2
                    item.pendingCount shouldBe expectedCount
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("state maps failed operations to descriptions with hasErrors") {
            runTest(testDispatcher) {
                val repo = FakePendingOperationRepository()
                repo.failed.value = listOf(failedOp("op1", entityId = "book1234xyz", lastError = "SYNC_NOT_FOUND"))
                val vm = buildViewModel(repo)

                vm.state.test {
                    var item = awaitItem()
                    while (!item.hasErrors) item = awaitItem()
                    item.hasErrors shouldBe true
                    val failedUi = item.failedOperations.single()
                    failedUi.id shouldBe "op1"
                    failedUi.isFailed shouldBe true
                    failedUi.error shouldBe "SYNC_NOT_FOUND"
                    failedUi.description shouldBe "Updating book book1234"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("RetryOperation and DismissOperation route to the repository") {
            runTest(testDispatcher) {
                val repo = FakePendingOperationRepository()
                val vm = buildViewModel(repo)

                vm.onEvent(SyncIndicatorUiEvent.RetryOperation("op1"))
                vm.onEvent(SyncIndicatorUiEvent.DismissOperation("op2"))
                testDispatcher.scheduler.advanceUntilIdle()

                repo.retried shouldBe listOf("op1")
                repo.dismissed shouldBe listOf("op2")
            }
        }

        test("RetryAll retries every failed operation") {
            runTest(testDispatcher) {
                val repo = FakePendingOperationRepository()
                repo.failed.value =
                    listOf(
                        failedOp("op1", entityId = "e1", lastError = "SYNC_NOT_FOUND"),
                        failedOp("op2", entityId = "e2", lastError = "SYNC_NOT_FOUND"),
                    )
                val vm = buildViewModel(repo)

                vm.state.test {
                    var item = awaitItem()
                    while (item.failedOperations.isEmpty()) item = awaitItem()
                    vm.onEvent(SyncIndicatorUiEvent.RetryAll)
                    testDispatcher.scheduler.advanceUntilIdle()
                    cancelAndIgnoreRemainingEvents()
                }

                repo.retried shouldBe listOf("op1", "op2")
            }
        }
    
        test("lastSyncedAtMs is the last successful sync, and stays through the next one") {
            runTest(testDispatcher) {
                val syncState = MutableStateFlow<SyncState>(SyncState.Idle)
                val syncRepository = mock<SyncRepository> { every { this@mock.syncState } returns syncState }
                val vm = SyncIndicatorViewModel(FakePendingOperationRepository(), syncRepository)

                vm.state.test {
                    awaitItem().lastSyncedAtMs shouldBe null
                    syncState.value = SyncState.Success(Timestamp(1_000L))
                    var item = awaitItem()
                    while (item.lastSyncedAtMs == null) item = awaitItem()
                    item.lastSyncedAtMs shouldBe 1_000L

                    syncState.value = SyncState.Syncing
                    item = awaitItem()
                    while (!item.isSyncing) item = awaitItem()
                    item.lastSyncedAtMs shouldBe 1_000L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("RefreshRequested asks the sync repository for a sync") {
            runTest(testDispatcher) {
                val syncRepository =
                    mock<SyncRepository> {
                        every { syncState } returns MutableStateFlow<SyncState>(SyncState.Idle)
                        everySuspend { sync() } returns AppResult.Success(Unit)
                    }
                val vm = SyncIndicatorViewModel(FakePendingOperationRepository(), syncRepository)

                vm.onEvent(SyncIndicatorUiEvent.RefreshRequested)
                testDispatcher.scheduler.advanceUntilIdle()

                verifySuspend(exactly(1)) { syncRepository.sync() }
            }
        }
    })
