package com.calypsan.listenup.server.rpcguard

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import java.util.UUID

/** Pulls `callId` from the active [MDCContext], or null if none is installed. */
suspend fun currentCorrelationId(): String? = currentCoroutineContext()[MDCContext]?.contextMap?.get("callId")

/** Generates a fresh correlation id when no `callId` is propagated from the Ktor pipeline. */
fun newCorrelationId(): String = UUID.randomUUID().toString()
