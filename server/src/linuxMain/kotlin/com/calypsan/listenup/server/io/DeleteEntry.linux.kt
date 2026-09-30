@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.ENOENT
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.errno
import platform.posix.lstat
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.strerror
import platform.posix.unlink

internal actual fun deleteEntry(path: Path) {
    val raw = path.toString()
    val isDirectory =
        memScoped {
            val s = alloc<stat>()
            // lstat, not stat: a link is described as itself, so it is unlinked, never followed.
            if (lstat(raw, s.ptr) != 0) {
                if (errno == ENOENT) return
                throw IOException("lstat $raw failed: ${strerror(errno)?.toKString()}")
            }
            s.st_mode.toInt() and S_IFMT == S_IFDIR
        }
    val status = if (isDirectory) rmdir(raw) else unlink(raw)
    if (status != 0 && errno != ENOENT) {
        throw IOException("delete $raw failed: ${strerror(errno)?.toKString()}")
    }
}
