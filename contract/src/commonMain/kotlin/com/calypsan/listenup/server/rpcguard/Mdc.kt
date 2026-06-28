package com.calypsan.listenup.server.rpcguard

/**
 * Runs [block] with the call's logging/correlation context augmented by [pairs].
 *
 * JVM augments the SLF4J `MDCContext` (unchanged). Native installs a [CorrelationContext] from the
 * `"correlationId"` pair so nested [currentCorrelationId] calls observe it.
 */
internal expect suspend fun <R> withMdc(
    vararg pairs: Pair<String, String>,
    block: suspend () -> R,
): R
