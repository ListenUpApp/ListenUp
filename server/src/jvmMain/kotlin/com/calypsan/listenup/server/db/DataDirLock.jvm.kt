package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.io.files.Path
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.nio.file.Path as NioPath

private val logger = loggerFor<JvmFileLockHandle>()

internal actual fun tryLockFile(lockFile: Path): FileLockHandle? {
    val channel =
        FileChannel.open(NioPath.of(lockFile.toString()), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock =
        try {
            channel.tryLock() // null when another PROCESS holds it
        } catch (e: OverlappingFileLockException) {
            logger.debug(e) { "Data dir $lockFile is already locked within this JVM" }
            null
        }
    if (lock == null) {
        channel.close()
        return null
    }
    return JvmFileLockHandle(channel, lock)
}

private class JvmFileLockHandle(
    private val channel: FileChannel,
    private val lock: FileLock,
) : FileLockHandle {
    override fun close() {
        try {
            lock.release()
        } catch (e: Exception) {
            logger.debug(e) { "ignoring data-dir lock release failure" }
        }
        try {
            channel.close()
        } catch (e: Exception) {
            logger.debug(e) { "ignoring data-dir lock channel close failure" }
        }
    }
}
