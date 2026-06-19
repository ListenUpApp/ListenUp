package com.calypsan.listenup.server.db.sqldelight

import kotlinx.coroutines.CoroutineDispatcher

/**
 * IO dispatcher for blocking SQL calls.
 *
 * JVM → [kotlinx.coroutines.Dispatchers.IO] (the bounded thread pool sized for blocking I/O).
 * Native → see `IoDispatcher.linuxX64.kt` for the platform choice and rationale.
 *
 * `Dispatchers.IO` is public on JVM but **internal** on Kotlin/Native in kotlinx-coroutines
 * 1.11.0, so a plain `Dispatchers.IO` reference in `commonMain` fails the native compile.
 * This expect/actual pair is the canonical KMP pattern for bridging that gap.
 */
internal expect val sqlIoDispatcher: CoroutineDispatcher
