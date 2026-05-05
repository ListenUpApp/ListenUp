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

class EndPlaybackSessionHandlerTest {
    private fun pendingOp(): PendingOperationEntity =
        PendingOperationEntity(
            id = "op-1",
            operationType = OperationType.END_PLAYBACK_SESSION,
            entityType = null,
            entityId = null,
            payload = "{}",
            batchKey = null,
            status = OperationStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L,
            attemptCount = 0,
            lastError = null,
        )

    @Test
    fun `handle calls syncApi endPlaybackSession with payload fields`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.endPlaybackSession("book-1", 60_000L) } returns Success(Unit)
            val handler = EndPlaybackSessionHandler(api)

            val result = handler.execute(pendingOp(), EndPlaybackSessionPayload(bookId = "book-1", durationMs = 60_000L))

            assertEquals(Success(Unit), result)
            verifySuspend { api.endPlaybackSession("book-1", 60_000L) }
        }

    @Test
    fun `handle returns Failure when syncApi returns Failure`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.endPlaybackSession("book-1", 60_000L) } returns
                Failure(TransportError.NetworkUnavailable())
            val handler = EndPlaybackSessionHandler(api)

            val result = handler.execute(pendingOp(), EndPlaybackSessionPayload(bookId = "book-1", durationMs = 60_000L))

            val failure = assertIs<Failure>(result)
            assertIs<TransportError.NetworkUnavailable>(failure.error)
        }

    @Test
    fun `tryCoalesce returns new payload when bookId matches`() {
        val handler = EndPlaybackSessionHandler(mock())
        val existing = EndPlaybackSessionPayload(bookId = "book-1", durationMs = 30_000L)
        val new = EndPlaybackSessionPayload(bookId = "book-1", durationMs = 60_000L)

        assertEquals(new, handler.tryCoalesce(pendingOp(), existing, new))
    }

    @Test
    fun `tryCoalesce returns null when bookId differs`() {
        val handler = EndPlaybackSessionHandler(mock())
        val existing = EndPlaybackSessionPayload(bookId = "book-1", durationMs = 30_000L)
        val new = EndPlaybackSessionPayload(bookId = "book-2", durationMs = 30_000L)

        assertEquals(null, handler.tryCoalesce(pendingOp(), existing, new))
    }

    @Test
    fun `handle rethrows CancellationException`() =
        runTest {
            val api: SyncApiContract = mock()
            everySuspend { api.endPlaybackSession("book-1", 60_000L) } throws
                kotlin.coroutines.cancellation.CancellationException("test cancel")
            val handler = EndPlaybackSessionHandler(api)

            var caught: kotlin.coroutines.cancellation.CancellationException? = null
            try {
                handler.execute(pendingOp(), EndPlaybackSessionPayload(bookId = "book-1", durationMs = 60_000L))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                caught = e
            }

            assertNotNull(caught)
            assertIs<kotlin.coroutines.cancellation.CancellationException>(caught)
        }
}
