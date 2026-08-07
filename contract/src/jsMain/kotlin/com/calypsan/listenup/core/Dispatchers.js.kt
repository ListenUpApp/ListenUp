package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// js has a single-threaded event loop — there is no elastic blocking-IO pool for
// Dispatchers.IO to name, and kotlinx-coroutines does not define one for this target.
// Default is the honest answer rather than an alias pretending otherwise.
actual val IODispatcher: CoroutineDispatcher = Dispatchers.Default
