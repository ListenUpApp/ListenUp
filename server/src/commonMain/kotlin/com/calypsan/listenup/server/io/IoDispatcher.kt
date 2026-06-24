package com.calypsan.listenup.server.io

import kotlinx.coroutines.CoroutineDispatcher

/**
 * IO dispatcher for blocking filesystem walks (`SystemFileSystem.list` / `statFile`).
 *
 * JVM → [kotlinx.coroutines.Dispatchers.IO] (the bounded thread pool sized for blocking I/O).
 * Native → [kotlinx.coroutines.Dispatchers.Default] (a real multi-threaded pool on K/N; there is
 * no public IO-pool equivalent on native in kotlinx-coroutines 1.11.0).
 *
 * `Dispatchers.IO` is public on JVM but **internal** on Kotlin/Native, so a plain `Dispatchers.IO`
 * reference in `commonMain` fails the native compile. This expect/actual pair is the canonical KMP
 * bridge — the same shape as `db/sqldelight/IoDispatcher.kt`'s `sqlIoDispatcher`.
 */
internal expect val fileIoDispatcher: CoroutineDispatcher
