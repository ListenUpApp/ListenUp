package com.calypsan.listenup.server.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// This actual IS the injection seam the module dispatches through, so it is where IO is named.
@Suppress("InjectDispatcher")
internal actual val fileIoDispatcher: CoroutineDispatcher = Dispatchers.IO
