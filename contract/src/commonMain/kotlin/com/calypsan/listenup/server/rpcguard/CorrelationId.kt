package com.calypsan.listenup.server.rpcguard

import kotlin.uuid.Uuid

/**
 * Pulls the active request's correlation id, or null if none is propagated.
 *
 * JVM reads it from the Ktor `CallId` -> SLF4J `MDCContext` bridge. Native reads it
 * from a [CorrelationContext] coroutine-context element; until the native Ktor pipeline installs
 * one, native returns null and the guard falls back to
 * [newCorrelationId].
 */
internal expect suspend fun currentCorrelationId(): String?

/** Generates a fresh correlation id when none is propagated from the pipeline. */
internal fun newCorrelationId(): String = Uuid.random().toString()
