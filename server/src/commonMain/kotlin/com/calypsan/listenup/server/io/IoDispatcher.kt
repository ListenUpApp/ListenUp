package com.calypsan.listenup.server.io

import com.calypsan.listenup.core.IODispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * IO dispatcher for blocking filesystem walks (`SystemFileSystem.list` / `statFile`) and
 * subprocess I/O.
 *
 * Delegates to [IODispatcher], which resolves to `Dispatchers.IO` on both JVM and
 * Kotlin/Native (via `kotlinx.coroutines.IO` — a dedicated elastic pool separate from
 * `Dispatchers.Default` so blocking disk I/O cannot starve Ktor or CPU-bound coroutines).
 */
internal val fileIoDispatcher: CoroutineDispatcher = IODispatcher
