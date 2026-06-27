package com.calypsan.listenup.server.db.sqldelight

import kotlin.coroutines.CoroutineContext

/**
 * Carries a per-call value through the coroutine context that [suspendTransaction] mirrors onto the
 * executing thread for the duration of the (synchronous) transaction body — so non-suspend code
 * inside the transaction can read it via [currentTransactionLocal].
 *
 * This is the cross-platform stand-in for kotlinx.coroutines' `ThreadContextElement`, which is
 * JVM-only: install with `withContext(TransactionLocal(value)) { … }` around a repository write and
 * the value becomes visible to the non-suspend `writePayload` running inside the transaction, even
 * after the `withContext(sqlIoDispatcher)` thread hop.
 */
internal class TransactionLocal(
    val value: Any?,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<TransactionLocal>
}

/** The [TransactionLocal.value] mirrored onto the current transaction thread, or null when none. */
internal expect fun currentTransactionLocal(): Any?

internal expect fun setTransactionLocal(value: Any?)
