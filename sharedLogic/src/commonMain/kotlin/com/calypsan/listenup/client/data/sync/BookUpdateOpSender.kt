package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.core.BookId

/**
 * Pushes a queued book edit to the server via
 * [com.calypsan.listenup.api.BookService.updateBook].
 *
 * The PATCH semantics are idempotent — every non-null field replaces the current
 * value — so the pending-operation queue may safely re-fire on retry. The
 * authoritative state returns via the SSE sync engine and reconciles through
 * [com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler]; the
 * sender therefore discards the (Unit) success payload.
 *
 * The [PendingOperation.payload] is a JSON-encoded [BookUpdate]. The sender
 * decodes it at dispatch time (not enqueue time) so the queue row is the single
 * durable representation of the pending write.
 */
internal class BookUpdateOpSender(
    private val rpcFactory: BookRpcFactory,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val patch = contractJson.decodeFromString(BookUpdate.serializer(), op.payload)
        return when (val result = rpcFactory.bookService().updateBook(BookId(op.entityId), patch)) {
            is WireAppResult.Success -> AppResult.Success(Unit)
            is WireAppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
