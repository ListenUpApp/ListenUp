package com.calypsan.listenup.client.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Launch [action] for every element of [items], letting at most `permits` of them run at once.
 *
 * Every item is launched immediately — the cap is on *execution*, not on enqueue — so the caller
 * still returns straight away and the queue drains at a rate the server can survive. Used by the
 * iOS download service, where an unbounded launch put one HTTP request per audio file in flight
 * simultaneously against a single self-hosted server.
 *
 * @return the launched jobs, in [items] order.
 */
internal fun <T> CoroutineScope.launchEachBounded(
    items: Iterable<T>,
    permits: Semaphore,
    action: suspend (T) -> Unit,
): List<Job> = items.map { item -> launch { permits.withPermit { action(item) } } }
