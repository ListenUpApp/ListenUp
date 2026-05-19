package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// iOS doesn't have Dispatchers.IO (it's internal), so we use Default
actual val IODispatcher: CoroutineDispatcher = Dispatchers.Default
