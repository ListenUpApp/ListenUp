package com.calypsan.listenup.server.sync

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor

/**
 * The per-transaction outbox of deferred firehose emissions. [ChangeBus] appends an emit thunk
 * here when a publish happens inside a transaction; [CommitPublishingInterceptor] flushes them
 * after the transaction commits. Shared between the two — keep them together.
 */
internal val FIREHOSE_OUTBOX_KEY = Key<MutableList<() -> Unit>>()

/**
 * Publishes firehose events only once their transaction has committed.
 *
 * Exposed 1.3 offers no per-transaction interceptor registration and wipes a transaction's
 * `UserData` between [beforeCommit] and [afterCommit], so the outbox is captured at [beforeCommit]
 * (keyed by transaction id) and flushed at [afterCommit] — i.e. after the rows are durable and
 * visible to the firehose's delivery-time `canAccess`. A rolled-back transaction's outbox is
 * discarded ([afterRollback]) so no phantom event is ever emitted. Auto-registered via
 * `META-INF/services/org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor`.
 *
 * Nested `suspendTransaction` reuses the outer transaction (one [Transaction], one outbox), so a
 * nested write's event flushes once at the outermost commit, in insertion (== revision) order.
 */
class CommitPublishingInterceptor : GlobalStatementInterceptor {
    override fun beforeCommit(transaction: Transaction) {
        val outbox = transaction.getUserData(FIREHOSE_OUTBOX_KEY) ?: return
        pending[transaction.id] = outbox.toList()
    }

    override fun afterCommit(transaction: Transaction) {
        pending.remove(transaction.id)?.forEach { it() }
    }

    override fun afterRollback(transaction: Transaction) {
        pending.remove(transaction.id)
    }

    private companion object {
        // Keyed by transaction id: beforeCommit captures (UserData is wiped before afterCommit),
        // afterCommit/afterRollback removes. Entries are tiny and always removed on either terminal
        // hook, so this does not grow unbounded.
        val pending = ConcurrentHashMap<String, List<() -> Unit>>()
    }
}
