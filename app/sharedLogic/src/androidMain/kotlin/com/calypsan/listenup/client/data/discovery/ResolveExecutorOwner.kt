package com.calypsan.listenup.client.data.discovery

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the single background executor NsdManager's API-34 `ServiceInfoCallback` resolution
 * path needs.
 *
 * Previously every `onServiceFound` minted its own `newSingleThreadExecutor()` and nothing
 * ever shut one down, so each discovered server left a live non-daemon thread behind for the
 * life of the process. One shared executor, released in `stopDiscovery`, fixes that without
 * changing resolution behaviour — NsdManager only needs somewhere to deliver callbacks.
 *
 * Synchronized because `acquire` runs on the NsdManager callback thread while `stopDiscovery`
 * can be called from the caller's thread.
 */
internal class ResolveExecutorOwner {
    private var executor: ExecutorService? = null

    /** The shared executor, created on first use. */
    fun acquire(): ExecutorService =
        synchronized(this) {
            executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        }

    /** Releases the executor so its thread can exit. Safe to call when none was acquired. */
    fun shutdown() {
        synchronized(this) {
            executor?.shutdown()
            executor = null
        }
    }
}
