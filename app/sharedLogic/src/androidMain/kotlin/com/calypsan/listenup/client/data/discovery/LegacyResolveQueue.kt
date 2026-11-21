package com.calypsan.listenup.client.data.discovery

/**
 * Runs the pre-API-34 mDNS resolves one at a time.
 *
 * The legacy `NsdManager.resolveService` handles a single resolve at a time and fails an
 * overlapping one with `FAILURE_ALREADY_ACTIVE`. Firing a resolve per found service therefore
 * dropped every server found while another was resolving — two ListenUp servers, or one announced
 * on two interfaces, and one of them stayed invisible until discovery restarted.
 *
 * [resolve] starts one resolve; its outcome must come back through [onResolved] or [onFailed] with
 * the same item. An outcome for anything other than the item in flight is ignored, so a callback
 * from a discovery that [clear] already ended cannot advance the next one. Callbacks arrive on the
 * NsdManager thread while [enqueue] runs on the discovery listener's, so all state is guarded by
 * one lock, and [resolve] is always invoked outside it.
 */
internal class LegacyResolveQueue<T : Any>(
    private val resolve: (T) -> Unit,
) {
    private data class Pending<T>(
        val item: T,
        val hasRetried: Boolean,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Pending<T>>()
    private var inFlight: Pending<T>? = null

    /** Queues [item], starting it at once when nothing else is resolving. */
    fun enqueue(item: T) {
        val next =
            synchronized(lock) {
                pending.addLast(Pending(item, hasRetried = false))
                startNextLocked()
            }
        next?.let(resolve)
    }

    /** The resolve of [item] finished; the next queued one starts. */
    fun onResolved(item: T) {
        val next =
            synchronized(lock) {
                if (inFlight?.item !== item) return
                inFlight = null
                startNextLocked()
            }
        next?.let(resolve)
    }

    /**
     * The resolve of [item] failed with [errorCode]. An overlap reported by the platform
     * ([FAILURE_ALREADY_ACTIVE], from a resolve this queue did not start) is retried once, behind
     * whatever is already waiting; any other failure, or a second overlap, gives the item up.
     */
    fun onFailed(
        item: T,
        errorCode: Int,
    ) {
        val next =
            synchronized(lock) {
                val failed = inFlight?.takeIf { it.item === item } ?: return
                inFlight = null
                if (errorCode == FAILURE_ALREADY_ACTIVE && !failed.hasRetried) {
                    pending.addLast(Pending(failed.item, hasRetried = true))
                }
                startNextLocked()
            }
        next?.let(resolve)
    }

    /** Drops everything queued and forgets the resolve in flight; its late outcome is ignored. */
    fun clear() {
        synchronized(lock) {
            pending.clear()
            inFlight = null
        }
    }

    private fun startNextLocked(): T? {
        if (inFlight != null) return null
        val next = pending.removeFirstOrNull() ?: return null
        inFlight = next
        return next.item
    }

    companion object {
        /** `NsdManager.FAILURE_ALREADY_ACTIVE`, inlined so the queue stays free of Android types. */
        const val FAILURE_ALREADY_ACTIVE = 3
    }
}
