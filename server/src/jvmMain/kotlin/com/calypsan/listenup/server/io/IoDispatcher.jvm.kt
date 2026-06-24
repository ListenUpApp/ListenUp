package com.calypsan.listenup.server.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val fileIoDispatcher: CoroutineDispatcher = Dispatchers.IO
