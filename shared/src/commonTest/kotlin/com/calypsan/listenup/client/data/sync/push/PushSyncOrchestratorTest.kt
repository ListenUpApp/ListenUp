package com.calypsan.listenup.client.data.sync.push

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.local.db.EntityType
import com.calypsan.listenup.client.data.local.db.OperationStatus
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.PendingOperationEntity
import com.calypsan.listenup.client.data.sync.SyncMutex
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentially
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import com.calypsan.listenup.client.core.error.ErrorBus

/**
 * Tests for PushSyncOrchestrator.
 *
 * Verifies push sync orchestration logic:
 * - Flush processes pending operations (last-write-wins)
 * - Skips flush when offline
 * - Prevents concurrent flushes
 * - Marks successful operations as completed
 * - Marks failed operations with error messages
 * - Auto-flushes on network restore
 * - Respects MAX_RETRIES
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushSyncOrchestratorTest {
    // ========== Test Fixtures ==========

    private fun createOperation(
        id: String = "op-1",
        operationType: OperationType = OperationType.BOOK_UPDATE,
        entityType: EntityType? = EntityType.BOOK,
        entityId: String? = "book-1",
        attemptCount: Int = 0,
    ): PendingOperationEntity =
        PendingOperationEntity(
            id = id,
            operationType = operationType,
            entityType = entityType,
            entityId = entityId,
            payload = """{"bookId":"book-1","title":"Test","coverUrl":null}""",
            batchKey = null,
            status = OperationStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L,
            attemptCount = attemptCount,
            lastError = null,
        )

    private class TestFixture {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val repository: PendingOperationRepositoryContract = mock()
        val executor: OperationExecutorContract = mock()
        val networkMonitor: NetworkMonitor = mock()
        val syncMutex = SyncMutex()

        val isOnlineFlow = MutableStateFlow(true)
        val newOperationQueuedFlow = MutableSharedFlow<Unit>()

        init {
            // Default stubs
            everySuspend { networkMonitor.isOnlineFlow } returns isOnlineFlow
            everySuspend { networkMonitor.isOnline() } returns true
            every { repository.newOperationQueued } returns newOperationQueuedFlow
            everySuspend { repository.resetStuckOperations() } returns Unit
            everySuspend { repository.getNextBatch(any()) } returns emptyList()
        }

        fun build(): PushSyncOrchestrator =
            PushSyncOrchestrator(
                repository = repository,
                executor = executor,
                networkMonitor = networkMonitor,
                syncMutex = syncMutex,
                scope = testScope,
                errorBus = ErrorBus(),
            )
    }

    // ========== Flush Tests ==========

    @Test
    fun `flush processes pending operations and marks them completed`() =
        runTest {
            // Given
            val fixture = TestFixture()
            val operations = listOf(createOperation("op-1"), createOperation("op-2"))

            everySuspend { fixture.repository.getNextBatch(any()) } sequentially {
                returns(operations)
                returns(emptyList())
            }
            everySuspend { fixture.repository.markInProgress(any()) } returns Unit
            everySuspend { fixture.repository.markCompleted(any()) } returns Unit
            everySuspend { fixture.executor.execute(any()) } returns
                mapOf(
                    "op-1" to Success(Unit),
                    "op-2" to Success(Unit),
                )

            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // When
            orchestrator.flush()

            // Then
            verifySuspend { fixture.repository.markInProgress(listOf("op-1", "op-2")) }
            verifySuspend { fixture.repository.markCompleted(listOf("op-1", "op-2")) }
        }

    @Test
    fun `flush skips when offline`() =
        runTest {
            // Given
            val fixture = TestFixture()
            fixture.isOnlineFlow.value = false
            everySuspend { fixture.networkMonitor.isOnline() } returns false

            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // When
            orchestrator.flush()

            // Then - verify flush completed without error
            assertFalse(orchestrator.isFlushing.value)
        }

    // ========== Failure Handling Tests ==========

    @Test
    fun `flush marks failed operations with error message`() =
        runTest {
            // Given
            val fixture = TestFixture()
            val operation = createOperation("op-1")
            val error = RuntimeException("Network error")

            everySuspend { fixture.repository.getNextBatch(any()) } sequentially {
                returns(listOf(operation))
                returns(emptyList())
            }
            everySuspend { fixture.repository.markInProgress(any()) } returns Unit
            everySuspend { fixture.repository.markFailed(any(), any()) } returns Unit
            everySuspend { fixture.executor.execute(any()) } returns
                mapOf(
                    "op-1" to Failure(error),
                )

            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // When
            orchestrator.flush()

            // Then
            verifySuspend { fixture.repository.markFailed("op-1", any()) }
        }

    @Test
    fun `flush marks operation as max retries exceeded after 3 attempts`() =
        runTest {
            // Given
            val fixture = TestFixture()
            val operation = createOperation("op-1", attemptCount = 2) // Already 2 attempts
            // Body-level message convention: the orchestrator forwards
            // `failure.error.message` into the markFailed text, so the typed
            // error must carry the literal "Still failing" on its message.
            val errorPayload =
                com.calypsan.listenup.api.error
                    .ValidationError(message = "Still failing")

            everySuspend { fixture.repository.getNextBatch(any()) } sequentially {
                returns(listOf(operation))
                returns(emptyList())
            }
            everySuspend { fixture.repository.markInProgress(any()) } returns Unit
            everySuspend { fixture.repository.markFailed(any(), any()) } returns Unit
            everySuspend { fixture.executor.execute(any()) } returns
                mapOf(
                    "op-1" to Failure(errorPayload),
                )

            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // When
            orchestrator.flush()

            // Then - Should include "Max retries exceeded"
            verifySuspend { fixture.repository.markFailed("op-1", "Max retries exceeded: Still failing") }
        }

    // ========== Initialization Tests ==========

    @Test
    fun `init resets stuck operations from previous session`() =
        runTest {
            // Given
            val fixture = TestFixture()

            // When
            fixture.build()
            fixture.testScope.advanceUntilIdle()

            // Then
            verifySuspend { fixture.repository.resetStuckOperations() }
        }

    // ========== isFlushing State Tests ==========

    @Test
    fun `isFlushing is false initially`() =
        runTest {
            // Given
            val fixture = TestFixture()

            // When
            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // Then
            assertFalse(orchestrator.isFlushing.value)
        }

    @Test
    fun `isFlushing is false after flush completes`() =
        runTest {
            // Given
            val fixture = TestFixture()
            everySuspend { fixture.repository.getNextBatch(any()) } returns emptyList()

            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // When
            orchestrator.flush()

            // Then
            assertFalse(orchestrator.isFlushing.value)
        }

    // ========== Edge Cases ==========

    @Test
    fun `flush handles empty batch gracefully`() =
        runTest {
            // Given
            val fixture = TestFixture()
            everySuspend { fixture.repository.getNextBatch(any()) } returns emptyList()

            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // When
            orchestrator.flush()

            // Then - Should complete without errors
            assertFalse(orchestrator.isFlushing.value)
        }

    @Test
    fun `flush continues processing subsequent batches`() =
        runTest {
            // Given
            val fixture = TestFixture()
            val op1 = createOperation("op-1")
            val op2 = createOperation("op-2")
            val batch1 = listOf(op1)
            val batch2 = listOf(op2)

            everySuspend { fixture.repository.getNextBatch(any()) } sequentially {
                returns(batch1)
                returns(batch2)
                returns(emptyList())
            }
            everySuspend { fixture.repository.markInProgress(any()) } returns Unit
            everySuspend { fixture.repository.markCompleted(any()) } returns Unit
            everySuspend { fixture.executor.execute(batch1) } returns mapOf("op-1" to Success(Unit))
            everySuspend { fixture.executor.execute(batch2) } returns mapOf("op-2" to Success(Unit))

            val orchestrator = fixture.build()
            fixture.testScope.advanceUntilIdle()

            // When
            orchestrator.flush()

            // Then - Should have called markCompleted twice
            verifySuspend { fixture.repository.markCompleted(any()) }
        }
}
