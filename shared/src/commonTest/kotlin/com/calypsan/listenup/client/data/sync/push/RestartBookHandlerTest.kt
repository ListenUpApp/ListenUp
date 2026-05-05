package com.calypsan.listenup.client.data.sync.push

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.local.db.OperationStatus
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.PendingOperationEntity
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.PlaybackProgressResponse
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RestartBookHandlerTest {
    private fun pendingOp(): PendingOperationEntity =
        PendingOperationEntity(
            id = "op-1",
            operationType = OperationType.RESTART_BOOK,
            entityType = null,
            entityId = "book-1",
            payload = "{}",
            batchKey = null,
            status = OperationStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L,
            attemptCount = 0,
            lastError = null,
        )

    private val emptyResponse =
        PlaybackProgressResponse(
            userId = "user-1",
            bookId = "book-1",
            currentPositionMs = 0L,
            progress = 0.0,
            isFinished = false,
            startedAt = "1970-01-01T00:00:00Z",
            lastPlayedAt = "1970-01-01T00:00:00Z",
            totalListenTimeMs = 0L,
            updatedAt = "1970-01-01T00:00:00Z",
        )

    @Test
    fun `execute calls syncApi restartBook with bookId`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.restartBook("book-1") } returns Success(emptyResponse)
            val handler = RestartBookHandler(api)

            val result = handler.execute(pendingOp(), RestartBookPayload(bookId = "book-1"))

            assertEquals(Success(Unit), result)
            verifySuspend { api.restartBook("book-1") }
        }

    @Test
    fun `execute returns Failure when syncApi returns Failure`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.restartBook("book-1") } returns Failure(TransportError.NetworkUnavailable())
            val handler = RestartBookHandler(api)

            val result = handler.execute(pendingOp(), RestartBookPayload(bookId = "book-1"))

            val failure = assertIs<Failure>(result)
            assertIs<TransportError.NetworkUnavailable>(failure.error)
        }

    @Test
    fun `tryCoalesce returns new payload when bookId matches`() {
        val handler = RestartBookHandler(mock())
        val existing = RestartBookPayload(bookId = "book-1")
        val new = RestartBookPayload(bookId = "book-1")

        assertEquals(new, handler.tryCoalesce(pendingOp(), existing, new))
    }

    @Test
    fun `tryCoalesce returns null when bookId differs`() {
        val handler = RestartBookHandler(mock())
        val existing = RestartBookPayload(bookId = "book-1")
        val new = RestartBookPayload(bookId = "book-2")

        assertEquals(null, handler.tryCoalesce(pendingOp(), existing, new))
    }

    @Test
    fun `execute rethrows CancellationException`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.restartBook("book-1") } throws
                kotlin.coroutines.cancellation.CancellationException("test cancel")
            val handler = RestartBookHandler(api)

            var caught: kotlin.coroutines.cancellation.CancellationException? = null
            try {
                handler.execute(pendingOp(), RestartBookPayload(bookId = "book-1"))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                caught = e
            }

            assertNotNull(caught)
        }
}
