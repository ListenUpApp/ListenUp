package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ReadingOrderBookMutation
import com.calypsan.listenup.api.dto.ReadingOrderFollowMutation
import com.calypsan.listenup.api.dto.ReadingOrderMutation
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** Each queued reading-order mutation reaches its one [ReadingOrderService] method (#962). */
class ReadingOrderOutboxSendersTest :
    FunSpec({
        test("an order's create, rename and delete dispatch under the op's order id") {
            runTest {
                val service = RecordingReadingOrderService()
                val senders = sendersFor(service)
                senders.send(OutboxChannels.ReadingOrders, "ro", ReadingOrderMutation.Create("cosmere", "URO"))
                senders.send(OutboxChannels.ReadingOrders, "ro", ReadingOrderMutation.Rename("URO 2"))
                senders.send(OutboxChannels.ReadingOrders, "ro", ReadingOrderMutation.Delete)
                service.calls shouldContainExactly listOf("create ro cosmere URO", "rename ro URO 2", "delete ro")
            }
        }

        test("a queued delete of an order the server no longer has drains as success; other failures surface") {
            runTest {
                val service = RecordingReadingOrderService(deleteResult = AppResult.Failure(ReadingOrderError.NotFound()))
                sendersFor(service)
                    .send(OutboxChannels.ReadingOrders, "ro", ReadingOrderMutation.Delete)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val forbidden = RecordingReadingOrderService(deleteResult = AppResult.Failure(ReadingOrderError.Forbidden()))
                sendersFor(forbidden)
                    .send(OutboxChannels.ReadingOrders, "ro", ReadingOrderMutation.Delete)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<ReadingOrderError.Forbidden>()
            }
        }

        test("membership add, remove and reorder dispatch with the payload's ids") {
            runTest {
                val service = RecordingReadingOrderService()
                val senders = sendersFor(service)
                senders.send(OutboxChannels.ReadingOrderBooks, "b1:ro", ReadingOrderBookMutation.Add("m1", "ro", "b1"))
                senders.send(OutboxChannels.ReadingOrderBooks, "b1:ro", ReadingOrderBookMutation.Remove("ro", "b1"))
                senders.send(OutboxChannels.ReadingOrderBooks, "ro", ReadingOrderBookMutation.Reorder("ro", listOf("b2", "b1")))
                service.calls shouldContainExactly listOf("add ro b1 m1", "remove ro b1", "reorder ro [b2, b1]")
            }
        }

        test("a queued choice rebuilds the sealed choice; clear dispatches clearReadingOrderChoice") {
            runTest {
                val service = RecordingReadingOrderService()
                val senders = sendersFor(service)
                senders.send(
                    OutboxChannels.ReadingOrderFollows,
                    "u1:mistborn",
                    ReadingOrderFollowMutation.Choose("mistborn", ReadingOrderChoiceKind.ORDER, "ro"),
                )
                senders.send(
                    OutboxChannels.ReadingOrderFollows,
                    "u1:mistborn",
                    ReadingOrderFollowMutation.Choose("mistborn", ReadingOrderChoiceKind.PUBLICATION),
                )
                senders.send(OutboxChannels.ReadingOrderFollows, "u1:mistborn", ReadingOrderFollowMutation.Clear("mistborn"))
                service.calls shouldContainExactly
                    listOf(
                        "choose mistborn ${ReadingOrderChoice.UserMade(ReadingOrderId("ro"))}",
                        "choose mistborn ${ReadingOrderChoice.PublicationOrder}",
                        "clear mistborn",
                    )
            }
        }

        test("the bindings cover exactly the three reading-order channels") {
            sendersFor(RecordingReadingOrderService()).keys.map { it.name } shouldContainExactly
                listOf("reading_orders", "reading_order_books", "reading_order_follows")
        }
    })

private fun sendersFor(service: ReadingOrderService): Map<OutboxChannel<*>, PendingOperationSender> =
    readingOrderOutboxBindings(RpcChannel.forTest(service)).toMap()

private suspend fun <T : Any> Map<OutboxChannel<*>, PendingOperationSender>.send(
    channel: OutboxChannel<T>,
    entityId: String,
    mutation: T,
): AppResult<Unit> =
    getValue(channel).send(
        PendingOperation(
            clientOpId = "op",
            domainName = channel.name,
            entityId = entityId,
            opType = "Update",
            payload = contractJson.encodeToString(channel.serializer, mutation),
            enqueuedAt = 0,
            failureCount = 0,
            ownerUserId = "u1",
        ),
    )

/** A fake service that records each call as a line and answers Success (or [deleteResult] for delete). */
private class RecordingReadingOrderService(
    private val deleteResult: AppResult<Unit> = AppResult.Success(Unit),
) : ReadingOrderService {
    val calls = mutableListOf<String>()

    override suspend fun createReadingOrder(
        id: ReadingOrderId,
        seriesId: SeriesId,
        name: String,
    ): AppResult<Unit> = record("create $id $seriesId $name")

    override suspend fun renameReadingOrder(
        id: ReadingOrderId,
        name: String,
    ): AppResult<Unit> = record("rename $id $name")

    override suspend fun deleteReadingOrder(id: ReadingOrderId): AppResult<Unit> {
        calls += "delete $id"
        return deleteResult
    }

    override suspend fun addBookToReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
        membershipId: String,
    ): AppResult<Unit> = record("add $id ${bookId.value} $membershipId")

    override suspend fun removeBookFromReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit> = record("remove $id ${bookId.value}")

    override suspend fun reorderReadingOrder(
        id: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> = record("reorder $id ${orderedBookIds.map { it.value }}")

    override suspend fun chooseReadingOrder(
        seriesId: SeriesId,
        choice: ReadingOrderChoice,
    ): AppResult<Unit> = record("choose $seriesId $choice")

    override suspend fun clearReadingOrderChoice(seriesId: SeriesId): AppResult<Unit> = record("clear $seriesId")

    override suspend fun countReadingOrderFollowers(id: ReadingOrderId): AppResult<Int> = AppResult.Success(0)

    private fun record(line: String): AppResult<Unit> {
        calls += line
        return AppResult.Success(Unit)
    }
}
