package com.calypsan.listenup.server.rpcguard

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext

/**
 * Runs [block] with the current [MDCContext] augmented by [pairs]. Existing MDC keys are
 * preserved; pairs override on conflict. `MDCContext`'s `restoreThreadContext` restores prior
 * state after [block].
 */
internal actual suspend fun <R> withMdc(
    vararg pairs: Pair<String, String>,
    block: suspend () -> R,
): R {
    val current = currentCoroutineContext()[MDCContext]?.contextMap.orEmpty()
    val merged = current + pairs.toMap()
    return withContext(MDCContext(merged)) { block() }
}
