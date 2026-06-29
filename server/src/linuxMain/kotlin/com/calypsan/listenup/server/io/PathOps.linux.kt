@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.free
import platform.posix.realpath

internal actual fun canonicalize(path: Path): Path =
    // realpath(…, null) mallocs the resolved buffer; null return means the path doesn't exist /
    // can't be resolved — stay lenient and hand back the input unchanged (JVM normalizes in-memory
    // and never fails on a missing path either).
    realpath(path.toString(), null)?.let {
        val resolved = it.toKString()
        free(it)
        Path(resolved)
    } ?: path
