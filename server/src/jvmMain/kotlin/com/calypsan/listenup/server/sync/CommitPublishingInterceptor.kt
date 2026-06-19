package com.calypsan.listenup.server.sync

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
 * Publishes firehose events only once their transaction has committed, so the firehose's
 * delivery-time `BookAccessPolicy.canAccess` (a read on a separate connection) sees the committed
 * rows instead of racing an uncommitted write.
 *
 * Exposed wipes a transaction's `UserData` on commit, EXCEPT the keys returned from
 * [keepUserDataInTransactionStoreOnCommit] — which are restored into `UserData` after the JDBC
 * commit and before [afterCommit]. So the outbox (keyed by [FIREHOSE_OUTBOX_KEY]) survives into
 * [afterCommit], where it is flushed — i.e. after the rows are durable. A rolled-back transaction
 * never reaches [keepUserDataInTransactionStoreOnCommit]/[afterCommit], so its outbox is discarded
 * and no phantom event is emitted. Auto-registered via
 * `META-INF/services/org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor`.
 *
 * Stateless and global: it fires for every transaction across every `Database` in the JVM, but the
 * stashed thunks are self-contained (each captures its own [ChangeBus] flow + payload), so a global
 * instance correctly serves any number of buses. Nested `suspendTransaction` reuses the outer
 * transaction (one [Transaction], one outbox), so a nested write's event flushes once at the
 * outermost commit, in insertion (== revision) order.
 */
class CommitPublishingInterceptor : GlobalStatementInterceptor {
    override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> =
        userData.filterKeys { it == FIREHOSE_OUTBOX_KEY }

    override fun afterCommit(transaction: Transaction) {
        transaction.getUserData(FIREHOSE_OUTBOX_KEY)?.forEach { it() }
    }
}
