package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// Kotlin/Native gained a real Dispatchers.IO (an elastic pool sized for blocking
// I/O) in kotlinx-coroutines 1.7 — reached via the `kotlinx.coroutines.IO`
// extension import. Mirror the Apple actual so IODispatcher means the same
// thing on every platform.
actual val IODispatcher: CoroutineDispatcher = Dispatchers.IO
