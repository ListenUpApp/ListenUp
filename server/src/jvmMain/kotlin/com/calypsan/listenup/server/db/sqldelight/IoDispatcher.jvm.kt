package com.calypsan.listenup.server.db.sqldelight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val sqlIoDispatcher: CoroutineDispatcher = Dispatchers.IO
