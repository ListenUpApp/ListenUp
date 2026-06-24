package com.calypsan.listenup.server.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// No public IO-pool equivalent is exposed on Kotlin/Native in kotlinx-coroutines 1.11.0.
// Dispatchers.Default is a real multi-threaded pool on native, so it is a safe dispatch site for
// the synchronous SystemFileSystem.list / statFile calls inside the Walker. Mirrors sqlIoDispatcher.
internal actual val fileIoDispatcher: CoroutineDispatcher = Dispatchers.Default
