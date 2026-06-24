package com.calypsan.listenup.server.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.nio.file.Path as NioPath

private val logger = KotlinLogging.logger {}

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
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}
