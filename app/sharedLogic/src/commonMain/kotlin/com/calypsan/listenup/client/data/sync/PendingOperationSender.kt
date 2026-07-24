package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult

/**
 * Dispatches one queued outbox operation to the server. The production instance is
 * the [DomainPendingOperationSender] router built by [outboxSender], holding one
 * [OutboxOpSender] per declared [com.calypsan.listenup.client.data.sync.domains.OutboxChannel].
 */
internal fun interface PendingOperationSender {
    suspend fun send(op: PendingOperation): AppResult<Unit>
}
