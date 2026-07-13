package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult

/**
 * Routes a [PendingOperation] to the concrete [PendingOperationSender] registered
 * for its [PendingOperation.domainName].
 *
 * A domain with no registered sender is a programmer error: an op was enqueued for a
 * domain that has no push implementation. The failure is surfaced as a
 * [SyncError.SyncFailed], which is retryable by contract (`isRetryable = true`) — so the
 * op BURNS its full retry budget ([MAX_RETRYABLE_ATTEMPTS] attempts) before dead-lettering,
 * exactly as [OutboxOpSender] documents for a corrupt payload. That waste is bounded and
 * never reached in practice: [outboxSender]'s completeness `require` makes a no-sender op
 * unconstructible in the production graph.
 */
internal class DomainPendingOperationSender(
    private val byDomain: Map<String, PendingOperationSender>,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> =
        byDomain[op.domainName]?.send(op)
            ?: AppResult.Failure(
                SyncError.SyncFailed(
                    debugInfo = "no sender registered for domain '${op.domainName}'",
                ),
            )
}
