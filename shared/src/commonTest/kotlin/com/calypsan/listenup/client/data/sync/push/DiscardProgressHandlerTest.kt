package com.calypsan.listenup.client.data.sync.push

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.local.db.OperationStatus
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.PendingOperationEntity
import com.calypsan.listenup.client.data.remote.SyncApiContract
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

class DiscardProgressHandlerTest {
    private fun pendingOp(): PendingOperationEntity =
        PendingOperationEntity(
            id = "op-1",
            operationType = OperationType.DISCARD_PROGRESS,
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

    @Test
    fun `execute calls syncApi discardProgress with bookId and keepHistory true`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.discardProgress("book-1", true) } returns Success(Unit)
            val handler = DiscardProgressHandler(api)

            val result = handler.execute(pendingOp(), DiscardProgressPayload(bookId = "book-1"))

            assertEquals(Success(Unit), result)
            verifySuspend { api.discardProgress("book-1", true) }
        }

    @Test
    fun `execute returns Failure when syncApi returns Failure`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.discardProgress("book-1", true) } returns Failure(TransportError.NetworkUnavailable())
            val handler = DiscardProgressHandler(api)

            val result = handler.execute(pendingOp(), DiscardProgressPayload(bookId = "book-1"))

            val failure = assertIs<Failure>(result)
            assertIs<TransportError.NetworkUnavailable>(failure.error)
        }

    @Test
    fun `tryCoalesce returns new payload when bookId matches`() {
        val handler = DiscardProgressHandler(mock())
        val existing = DiscardProgressPayload(bookId = "book-1")
        val new = DiscardProgressPayload(bookId = "book-1")

        assertEquals(new, handler.tryCoalesce(pendingOp(), existing, new))
    }

    @Test
    fun `tryCoalesce returns null when bookId differs`() {
        val handler = DiscardProgressHandler(mock())
        val existing = DiscardProgressPayload(bookId = "book-1")
        val new = DiscardProgressPayload(bookId = "book-2")

        assertEquals(null, handler.tryCoalesce(pendingOp(), existing, new))
    }

    @Test
    fun `execute rethrows CancellationException`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.discardProgress("book-1", true) } throws
                kotlin.coroutines.cancellation.CancellationException("test cancel")
            val handler = DiscardProgressHandler(api)

            var caught: kotlin.coroutines.cancellation.CancellationException? = null
            try {
                handler.execute(pendingOp(), DiscardProgressPayload(bookId = "book-1"))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                caught = e
            }

            assertNotNull(caught)
        }
}
