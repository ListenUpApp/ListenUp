@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.lstat
import platform.posix.stat

internal actual fun isSymlink(path: Path): Boolean =
    memScoped {
        val s = alloc<stat>()
        // lstat does NOT follow the link, so st_mode describes the link itself.
        if (lstat(path.toString(), s.ptr) != 0) return false
        // st_mode is an unsigned mode_t; convert to Int before the bitwise mask.
        // No octal literals in Kotlin — use the named S_IFMT / S_IFLNK constants.
        (s.st_mode.toInt() and S_IFMT) == S_IFLNK
    }
