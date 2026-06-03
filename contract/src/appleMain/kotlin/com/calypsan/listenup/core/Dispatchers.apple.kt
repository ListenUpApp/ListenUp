package com.calypsan.listenup.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// Kotlin/Native gained a real Dispatchers.IO (an elastic pool sized for blocking
// I/O) in kotlinx-coroutines 1.7 — reached via the `kotlinx.coroutines.IO`
// extension import. Use it so IODispatcher means the same thing on every
// platform; the old Dispatchers.Default fallback put blocking I/O on the
// CPU-bound pool, where it could starve compute coroutines.
actual val IODispatcher: CoroutineDispatcher = Dispatchers.IO
