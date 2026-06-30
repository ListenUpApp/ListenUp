package com.calypsan.listenup.server.db.sqldelight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Dispatchers.IO is internal on Kotlin/Native in kotlinx-coroutines 1.11.0 — no public IO-pool
// equivalent is exposed on native. Dispatchers.Default is a real multi-threaded pool on native
// (unlike the single-threaded legacy native model), so it is a safe dispatch site for the
// synchronous SQLiter driver calls inside suspendTransaction. Revisit if a dedicated IO pool
// becomes public in a future coroutines release.
internal actual val sqlIoDispatcher: CoroutineDispatcher = Dispatchers.Default
