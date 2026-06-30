@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
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
import platform.posix.errno
import platform.posix.open
import platform.posix.strerror

internal actual fun tryLockFile(lockFile: Path): FileLockHandle? {
    val fd = open(lockFile.toString(), O_CREAT or O_WRONLY, (S_IRUSR or S_IWUSR).toUInt())
    if (fd < 0) {
        // open() never fails because the lock is *held* — that's only knowable from flock() below.
        // A negative fd means the lock file can't be opened/created at all (typically EACCES on a
        // data dir the nonroot server can't write). Surface it, rather than returning null, which the
        // caller would otherwise misreport as "another server is already using the data directory".
        val err = errno
        error("cannot open data-dir lock file $lockFile: ${strerror(err)?.toKString() ?: "errno=$err"}")
    }
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
