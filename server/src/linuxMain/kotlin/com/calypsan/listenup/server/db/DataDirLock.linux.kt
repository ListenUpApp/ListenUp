@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.linux.LOCK_EX
import platform.linux.LOCK_NB
import platform.linux.LOCK_UN
import platform.linux.flock
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.open

internal actual fun tryLockFile(lockFile: Path): FileLockHandle? {
    val fd = open(lockFile.toString(), O_CREAT or O_WRONLY, (S_IRUSR or S_IWUSR).toUInt())
    if (fd < 0) return null
    if (flock(fd, LOCK_EX or LOCK_NB) != 0) {
        close(fd) // another open file description holds it (cross-process OR same-process second open)
        return null
    }
    return PosixFileLockHandle(fd)
}

private class PosixFileLockHandle(
    private val fd: Int,
) : FileLockHandle {
    override fun close() {
        flock(fd, LOCK_UN)
        close(fd)
    }
}
