package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.dto.ReadingOrderBookMutation
import com.calypsan.listenup.api.dto.ReadingOrderFollowMutation
import com.calypsan.listenup.api.dto.ReadingOrderMutation
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId

/**
 * The outbox sender bindings for the three reading-order channels (#962), each dispatching a queued
 * mutation to its one [ReadingOrderService] method. Every method is idempotent on replay server-side;
 * a delete of an order the server no longer has drains as success ([orSuccessIfNotFound]).
 */
internal fun readingOrderOutboxBindings(
    channel: RpcChannel<ReadingOrderService>,
): List<Pair<OutboxChannel<*>, PendingOperationSender>> =
    listOf(
        // The op's entityId is the client-minted order id; Create carries the rest of the order.
        outboxBinding(OutboxChannels.ReadingOrders) { id, mutation ->
            when (mutation) {
                is ReadingOrderMutation.Create -> {
                    channel.call { service ->
                        service.createReadingOrder(
                            ReadingOrderId(id),
                            SeriesId(mutation.seriesId),
                            mutation.name,
                        )
                    }
                }

                is ReadingOrderMutation.Rename -> {
                    channel.call { it.renameReadingOrder(ReadingOrderId(id), mutation.name) }
                }

                is ReadingOrderMutation.Delete -> {
                    channel.call { it.deleteReadingOrder(ReadingOrderId(id)) }.orSuccessIfNotFound()
                }
            }
        },
        // Add and remove are keyed by the junction, reorder by the order; the payload carries the ids.
        outboxBinding(OutboxChannels.ReadingOrderBooks) { _, mutation ->
            when (mutation) {
                is ReadingOrderBookMutation.Add -> {
                    channel.call { service ->
                        service.addBookToReadingOrder(
                            ReadingOrderId(mutation.readingOrderId),
                            BookId(mutation.bookId),
                            mutation.membershipId,
                        )
                    }
                }

                is ReadingOrderBookMutation.Remove -> {
                    channel.call { service ->
                        service.removeBookFromReadingOrder(
                            ReadingOrderId(mutation.readingOrderId),
                            BookId(mutation.bookId),
                        )
                    }
                }

                is ReadingOrderBookMutation.Reorder -> {
                    channel.call { service ->
                        service.reorderReadingOrder(
                            ReadingOrderId(mutation.readingOrderId),
                            mutation.orderedBookIds.map(::BookId),
                        )
                    }
                }
            }
        },
        // The op's entityId is the follow id; the payload carries the series.
        outboxBinding(OutboxChannels.ReadingOrderFollows) { _, mutation ->
            when (mutation) {
                is ReadingOrderFollowMutation.Choose -> {
                    channel.call { service ->
                        service.chooseReadingOrder(
                            SeriesId(mutation.seriesId),
                            ReadingOrderChoice.from(mutation.kind, mutation.readingOrderId?.let(::ReadingOrderId)),
                        )
                    }
                }

                is ReadingOrderFollowMutation.Clear -> {
                    channel.call { it.clearReadingOrderChoice(SeriesId(mutation.seriesId)) }
                }
            }
        },
    )
