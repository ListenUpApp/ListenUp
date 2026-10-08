package com.calypsan.listenup.server.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatcher for CPU-bound work — password hashing, image decode/resize/encode — that must not
 * sit on a thread meant to be serving other requests. The CPU-side sibling of `fileIoDispatcher`
 * and `sqlIoDispatcher`: every such call site dispatches through this one seam rather than naming
 * [Dispatchers.Default] itself. No expect/actual needed — [Dispatchers.Default] is public everywhere.
 *
 * This is the seam the rest of the module dispatches through, so it is where Default is named.
 */
@Suppress("InjectDispatcher")
internal val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
