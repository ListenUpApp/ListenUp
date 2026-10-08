package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// This is the injection point itself: the one place the platform IO dispatcher is named.
@Suppress("InjectDispatcher")
actual val IODispatcher: CoroutineDispatcher = Dispatchers.IO
