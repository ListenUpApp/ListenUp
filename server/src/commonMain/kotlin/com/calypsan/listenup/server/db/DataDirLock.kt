package com.calypsan.listenup.server.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

/** A handle to a held [DataDirLock]; closing it releases the OS lock. */
internal interface FileLockHandle : AutoCloseable

/**
 * Takes an exclusive, non-blocking OS advisory lock on [lockFile], or returns `null` when another
 * process — or another lock within this process — already holds it. JVM = `FileChannel.tryLock`;
 * native = `flock(LOCK_EX or LOCK_NB)`.
 */
internal expect fun tryLockFile(lockFile: Path): FileLockHandle?

/**
 * An exclusive, OS-level lock on a ListenUp data directory, so only one server process can use a
 * given data home at a time.
 *
 * Two instances sharing one `$LISTENUP_HOME` — a stale JVM that wasn't fully killed plus a fresh
 * boot — race the scan-spool: the new boot's `sweepOrphans()` could delete a live scan's covers out
 * from under the other instance's persist. That is the cause of #703's spooled-cover 404s. This lock
 * turns that silent corruption into an explicit, fail-fast startup error.
 *
 * Backed by a [FileLockHandle] on `<dataHome>/.lock`: the OS releases it automatically on process
 * death, and [close] releases it on a clean shutdown. [tryAcquire] returns `false` when another
 * process — or another [DataDirLock] in this process — already holds it.
 */
class DataDirLock(
    private val lockFile: Path,
) : AutoCloseable {
    private var handle: FileLockHandle? = null

    /**
     * Attempts to take the lock. Returns `true` on success; `false` when another instance already
     * holds this data home. Never throws for the "already held" case.
     */
    fun tryAcquire(): Boolean {
        lockFile.parent?.let { SystemFileSystem.createDirectories(it) }
        val acquired = tryLockFile(lockFile) ?: return false
        handle = acquired
        return true
    }

    override fun close() {
        try {
            handle?.close()
        } catch (e: Exception) {
            logger.debug(e) { "ignoring data-dir lock-handle close failure" }
        }
        handle = null
    }

    companion object {
        const val LOCK_FILENAME = ".lock"

        /** The lock guarding [dataHome], rooted at `<dataHome>/.lock`. */
        fun forDataHome(dataHome: Path): DataDirLock = DataDirLock(Path(dataHome, LOCK_FILENAME))
    }
}
