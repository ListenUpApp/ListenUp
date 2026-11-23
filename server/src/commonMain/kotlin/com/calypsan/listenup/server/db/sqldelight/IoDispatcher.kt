package com.calypsan.listenup.server.db.sqldelight

import com.calypsan.listenup.core.IODispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * IO dispatcher for blocking SQL calls inside `suspendTransaction`.
 *
 * Delegates to [IODispatcher], which resolves to `Dispatchers.IO` on both JVM and
 * Kotlin/Native (via `kotlinx.coroutines.IO` — a dedicated elastic pool separate from
 * `Dispatchers.Default` so blocking SQLite calls cannot starve Ktor or CPU-bound coroutines).
 */
internal val sqlIoDispatcher: CoroutineDispatcher = IODispatcher
