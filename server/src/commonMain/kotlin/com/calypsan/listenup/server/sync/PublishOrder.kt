package com.calypsan.listenup.server.sync

import app.cash.sqldelight.TransactionCallbacks
import com.calypsan.listenup.api.sync.SyncEvent

/**
 * Registers [event] to reach the live tail after this transaction commits, in revision order.
 *
 * This is the **only** supported way for a storage write to reach [ChangeBus]'s data channel.
 * It does the three things a post-commit emit has to do together, so no call site can get one
 * of them wrong:
 *
 *  - **reserves the publish slot now**, inside the transaction. The caller has just bumped the
 *    global revision counter, so SQLite's write lock is still held and no other writer can have
 *    taken a revision in between — which is what makes slot order revision order (see
 *    [ChangeBus.reserve]);
 *  - **publishes on commit**, via an `afterCommit` hook, so the firehose's delivery-time
 *    `BookAccessPolicy` read never races an uncommitted row;
 *  - **discards on rollback**, via an `afterRollback` hook, so a rolled-back write announces
 *    nothing and — just as important — does not leave later writes queued behind a slot that
 *    will never resolve.
 *
 * SQLDelight runs exactly one of the two hook lists when the outermost transaction ends, and a
 * nested transaction transfers both lists to its enclosing one, so the slot is resolved exactly
 * once however the transactions nest.
 */
internal fun <T : Any> TransactionCallbacks.emitInPublishOrder(
    bus: ChangeBus,
    repo: SyncableRepo<T>,
    event: SyncEvent<T>,
    userId: String? = null,
) {
    val slot = bus.reserve(repo = repo, event = event, userId = userId)
    afterCommit { bus.release(slot) }
    afterRollback { bus.discard(slot) }
}
