package com.calypsan.listenup.server.rpcguard

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext

/** Pulls `callId` from the active [MDCContext], or null if none is installed. */
internal actual suspend fun currentCorrelationId(): String? =
    currentCoroutineContext()[MDCContext]?.contextMap?.get("callId")
