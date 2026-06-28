package com.calypsan.listenup.server.rpcguard

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Carries the active request's correlation id through the coroutine context on native targets,
 * standing in for the JVM's SLF4J `MDCContext`.
 */
internal class CorrelationContext(
    val correlationId: String,
) : AbstractCoroutineContextElement(CorrelationContext) {
    companion object Key : CoroutineContext.Key<CorrelationContext>
}

internal actual suspend fun currentCorrelationId(): String? =
    currentCoroutineContext()[CorrelationContext]?.correlationId

internal actual suspend fun <R> withMdc(
    vararg pairs: Pair<String, String>,
    block: suspend () -> R,
): R {
    val cid = pairs.toMap()["correlationId"]
    return if (cid != null) withContext(CorrelationContext(cid)) { block() } else block()
}
