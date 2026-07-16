package com.calypsan.listenup.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler

private val logger = KotlinLogging.logger {}

/**
 * Last-resort handler for exceptions that escape a coroutine launched on an application-lifetime
 * [kotlinx.coroutines.CoroutineScope].
 *
 * **Why this exists (iOS stability, not cosmetics).** On Kotlin/Native an uncaught coroutine
 * exception routes to `propagateExceptionFinalResort`, which **terminates the process**. So on iOS a
 * single transient failure in a fire-and-forget `launch` — the realtime RPC socket dropping when the
 * server is unreachable, a best-effort background download throwing, a Keychain race — crashes the
 * whole app, where the JVM/Android runtime would merely log it. Installing this handler on every
 * long-lived scope converts that class of crash into a loud log line and lets the app keep running.
 *
 * It is a safety net, **not** a licence to swallow errors silently: every escape is logged at ERROR
 * with its stack trace, so issues stay visible (honest over silent). `CancellationException` is never
 * delivered here — normal cancellation is not an uncaught failure — so it needs no special handling.
 *
 * Pair it with a [kotlinx.coroutines.SupervisorJob] (so one failed child can't cancel its siblings):
 * `CoroutineScope(SupervisorJob() + dispatcher + appCoroutineExceptionHandler)`.
 */
val appCoroutineExceptionHandler: CoroutineExceptionHandler =
    CoroutineExceptionHandler { context, throwable ->
        logger.error(throwable) {
            "Uncaught exception escaped a coroutine on an app-lifetime scope ($context); " +
                "handled to keep the app alive (this would terminate the process on Kotlin/Native)"
        }
    }
