package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform-specific IO dispatcher.
 *
 * On Android/JVM: Uses Dispatchers.IO (optimized for blocking I/O operations)
 * On iOS/Native: Uses Dispatchers.IO via kotlinx.coroutines.IO (elastic pool sized for blocking I/O)
 *
 * Use this instead of Dispatchers.IO directly in common code to ensure
 * cross-platform compatibility.
 */
expect val IODispatcher: CoroutineDispatcher
