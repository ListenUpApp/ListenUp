package com.calypsan.listenup.server.db

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * An exclusive, OS-level lock on a ListenUp data directory, so only one server process can use a
 * given data home at a time.
 *
 * Two instances sharing one `$LISTENUP_HOME` — a stale JVM that wasn't fully killed plus a fresh
 * boot — race the scan-spool: the new boot's `sweepOrphans()` could delete a live scan's covers out
 * from under the other instance's persist. That is the cause of #703's spooled-cover 404s. This lock
 * turns that silent corruption into an explicit, fail-fast startup error.
 *
 * Backed by a [FileLock] on `<dataHome>/.lock`: the OS releases it automatically on process death,
 * and [close] releases it on a clean shutdown. [tryAcquire] returns `false` when another process —
 * or another [DataDirLock] in this JVM — already holds it.
 */
class DataDirLock(
    private val lockFile: Path,
) : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * Attempts to take the lock. Returns `true` on success; `false` when another instance already
     * holds this data home. Never throws for the "already held" case — both the cross-process
     * (`tryLock` returns null) and same-JVM ([OverlappingFileLockException]) outcomes map to `false`.
     */
    fun tryAcquire(): Boolean {
        Files.createDirectories(lockFile.parent)
        val ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired =
            try {
                ch.tryLock() // null when another PROCESS holds the lock
            } catch (e: OverlappingFileLockException) {
                // Another DataDirLock in THIS jvm already holds it — same "already locked" outcome.
                logger.debug(e) { "Data dir $lockFile is already locked within this JVM" }
                null
            }
        if (acquired == null) {
            ch.close()
            return false
        }
        channel = ch
        lock = acquired
        return true
    }

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    companion object {
        const val LOCK_FILENAME = ".lock"

        /** The lock guarding [dataHome], rooted at `<dataHome>/.lock`. */
        fun forDataHome(dataHome: Path): DataDirLock = DataDirLock(dataHome.resolve(LOCK_FILENAME))
    }
}
