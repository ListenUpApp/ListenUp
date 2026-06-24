@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.stat

private const val MILLIS_PER_SECOND = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L

internal actual fun statFile(path: Path): FileAttributes? =
    memScoped {
        val s = alloc<stat>()
        if (stat(path.toString(), s.ptr) != 0) return null
        FileAttributes(
            size = s.st_size,
            mtimeMs = s.st_mtim.tv_sec * MILLIS_PER_SECOND + s.st_mtim.tv_nsec / NANOS_PER_MILLI,
            inode = s.st_ino.toLong(),
        )
    }
