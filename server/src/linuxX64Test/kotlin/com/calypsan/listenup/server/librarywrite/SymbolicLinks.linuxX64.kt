@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.librarywrite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.errno
import platform.posix.strerror
import platform.posix.symlink

internal actual fun createSymbolicLink(
    link: Path,
    target: Path,
) {
    check(symlink(target.toString(), link.toString()) == 0) {
        "symlink($target, $link) failed: ${strerror(errno)?.toKString()}"
    }
}
