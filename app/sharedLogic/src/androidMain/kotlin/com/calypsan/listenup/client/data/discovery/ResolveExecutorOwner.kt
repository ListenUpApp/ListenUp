package com.calypsan.listenup.client.data.discovery

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the one background executor NsdManager's API-34 `ServiceInfoCallback` resolution path
 * delivers callbacks on.
 *
 * It is never shut down, on purpose. NsdManager keeps the executor it was handed and posts late
 * callbacks (`onServiceUpdated`, `onServiceInfoCallbackUnregistered`) on its own ConnectivityThread
 * after discovery has stopped; a shut-down executor rejects them, and a rejection on a system thread
 * is an uncaught exception that kills the app. That happened 34 times in four days on one phone,
 * and twice it landed between the server rotating a refresh token and the app saving it — the
 * session was then revoked as a replay the next time the app opened.
 *
 * Not leaking threads — the reason this class exists (every `onServiceFound` once minted its own
 * executor) — is kept by letting the single thread time out when idle rather than by shutting the
 * executor down. The unbounded queue means `execute` never rejects.
 */
internal class ResolveExecutorOwner(
    keepAlive: Duration = 30.seconds,
) {
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            keepAlive.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        ) { runnable -> Thread(runnable, "nsd-resolve").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }

    /** The shared executor: the same instance for the life of the process, and it never rejects. */
    fun acquire(): Executor = executor

    /** Threads currently alive in the executor — zero once it has sat idle past its keep-alive. */
    internal val liveThreads: Int get() = executor.poolSize
}
