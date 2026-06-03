package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult

/**
 * Boundary the queue calls to actually push an op to the server. Books-C
 * onwards register concrete senders per (domain, opType); during the
 * Renovation phase no domain has a write API yet so the registered sender
 * is a no-op stub. Tests inject a fake.
 */
fun interface PendingOperationSender {
    suspend fun send(op: PendingOperation): AppResult<Unit>
}
