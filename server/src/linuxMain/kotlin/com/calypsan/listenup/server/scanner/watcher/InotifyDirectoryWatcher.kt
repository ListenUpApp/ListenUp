package com.calypsan.listenup.server.scanner.watcher

import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.linux.IN_CREATE
import platform.linux.IN_DELETE
import platform.linux.IN_IGNORED
import platform.linux.EFD_NONBLOCK
import platform.linux.IN_ISDIR
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVED_FROM
import platform.linux.IN_MOVED_TO
import platform.linux.IN_NONBLOCK
import platform.linux.IN_Q_OVERFLOW
import platform.linux.eventfd
import platform.linux.inotify_add_watch
import platform.linux.inotify_event
import platform.linux.inotify_init1
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.errno
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.write

private val logger = loggerFor<InotifyDirectoryWatcher>()

/** inotify mask we register per directory — the create/modify/delete + rename pair the JVM WatchService surfaces. */
private val WATCH_MASK: UInt = (IN_CREATE or IN_MODIFY or IN_DELETE or IN_MOVED_TO or IN_MOVED_FROM).toUInt()

/** Read buffer for a batch of `inotify_event`s. Comfortably holds many events per `read`. */
private const val EVENT_BUFFER_BYTES = 64 * 1024

/**
 * Kotlin/Native [LowLevelDirectoryWatcher] over Linux **inotify** — the native peer of the JVM
 * `WatchService`-backed [RecursiveDirectoryWatcher], surfacing the same Create/Modify/Delete model.
 *
 * The whole inotify API lives in K/N's `platform.linux` (no custom cinterop). One non-recursive
 * watch is added per directory ([add]); the kernel reports a child's name + the watch descriptor, so
 * a `wd → directory` map recovers the parent. inotify's `MOVED_TO`/`MOVED_FROM` map to Create/Delete
 * exactly as the JVM WatchService does. A created sub-directory is registered and walked, emitting
 * synthetic Creates for files already inside it — closing the copy-before-watch race. `IN_Q_OVERFLOW`
 * re-emits the directory as a Create so its subtree re-walks; `IN_IGNORED` drops a vanished watch.
 *
 * The event loop runs on a dedicated thread and blocks in `poll()` over the inotify fd and a shutdown
 * `eventfd`; [close] writes the eventfd to wake the loop — a race-free alternative to closing the fd
 * out from under a blocking `read`.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class InotifyDirectoryWatcher(
    private val scope: CoroutineScope,
) : LowLevelDirectoryWatcher {
    private val emissions = MutableSharedFlow<DirectoryWatchEvent>(extraBufferCapacity = 256)
    override val onEventFlow: Flow<DirectoryWatchEvent> = emissions.asSharedFlow()

    private val inotifyFd: Int = inotify_init1(IN_NONBLOCK)

    // `convert()` adapts each argument to the per-arch C integer type: `eventfd`'s initval is UInt on
    // linuxArm64 but Int on linuxX64, and EFD_NONBLOCK's signedness likewise differs. Hardcoding
    // `0`/`.toInt()` compiles on x64 only; `convert()` is value-identical there and portable to arm64.
    private val shutdownFd: Int = eventfd(0.convert(), EFD_NONBLOCK.convert())

    private val stateLock = SynchronizedObject()
    private val wdToDir = mutableMapOf<Int, String>()

    private val loopLock = SynchronizedObject()
    private var loop: Job? = null

    private val watcherThread = newSingleThreadContext("inotify-watcher")

    override suspend fun add(directory: String) {
        register(directory)
        synchronized(loopLock) {
            if (loop == null) loop = startLoop()
        }
    }

    private fun register(dir: String) {
        val wd = inotify_add_watch(inotifyFd, dir, WATCH_MASK)
        if (wd < 0) {
            logger.warn { "inotify_add_watch failed for $dir (errno=$errno)" }
            return
        }
        synchronized(stateLock) { wdToDir[wd] = dir }
    }

    private fun startLoop(): Job =
        scope.launch(watcherThread) {
            memScoped {
                val buffer = allocArray<ByteVar>(EVENT_BUFFER_BYTES)
                val fds = allocArray<pollfd>(2)
                fds[0].fd = inotifyFd
                fds[0].events = POLLIN.toShort()
                fds[1].fd = shutdownFd
                fds[1].events = POLLIN.toShort()
                while (isActive) {
                    val ready = poll(fds, 2.convert(), -1)
                    if (ready < 0) {
                        if (errno == EINTR) continue
                        logger.warn { "inotify poll failed (errno=$errno) — stopping watcher" }
                        break
                    }
                    if (fds[1].revents.toInt() and POLLIN != 0) break // shutdown signalled
                    if (fds[0].revents.toInt() and POLLIN != 0) drainEvents(buffer)
                }
            }
        }

    private suspend fun drainEvents(buffer: kotlinx.cinterop.CArrayPointer<ByteVar>) {
        val headerSize = sizeOf<inotify_event>().toInt()
        while (true) {
            val bytesRead = read(inotifyFd, buffer, EVENT_BUFFER_BYTES.convert())
            if (bytesRead <= 0L) break // EAGAIN: no more queued events
            var offset = 0L
            while (offset < bytesRead) {
                val event = (buffer + offset)!!.reinterpret<inotify_event>().pointed
                val nameLen = event.len.toInt()
                if (nameLen < 0 || offset + headerSize + nameLen > bytesRead) {
                    logger.warn {
                        "inotify event with invalid name length ($nameLen) at offset $offset of $bytesRead bytes — dropping rest of batch"
                    }
                    break
                }
                val name =
                    if (nameLen > 0) {
                        (buffer + offset + headerSize)!!.reinterpret<ByteVar>().toKString()
                    } else {
                        ""
                    }
                dispatch(event.wd, event.mask, name)
                offset += headerSize + nameLen
            }
        }
    }

    private suspend fun dispatch(
        wd: Int,
        mask: UInt,
        name: String,
    ) {
        if (mask and IN_IGNORED.toUInt() != 0u) {
            // The watch was auto-removed (its directory was deleted/moved) — drop it.
            synchronized(stateLock) { wdToDir.remove(wd) }
            return
        }
        val dir = synchronized(stateLock) { wdToDir[wd] } ?: return
        if (mask and IN_Q_OVERFLOW.toUInt() != 0u) {
            // Events were lost — re-emit the directory so its subtree is re-walked.
            emissions.emit(DirectoryWatchEvent(dir, dir, DirectoryWatchEventKind.Create))
            return
        }
        if (name.isEmpty()) return
        val child = "$dir/$name"
        val isDir = mask and IN_ISDIR.toUInt() != 0u
        when {
            mask and (IN_CREATE or IN_MOVED_TO).toUInt() != 0u -> {
                emissions.emit(DirectoryWatchEvent(dir, child, DirectoryWatchEventKind.Create))
                if (isDir) registerNewSubtree(child)
            }

            mask and IN_MODIFY.toUInt() != 0u -> {
                emissions.emit(DirectoryWatchEvent(dir, child, DirectoryWatchEventKind.Modify))
            }

            mask and (IN_DELETE or IN_MOVED_FROM).toUInt() != 0u -> {
                emissions.emit(DirectoryWatchEvent(dir, child, DirectoryWatchEventKind.Delete))
            }
        }
    }

    /**
     * Registers [rootDir] and every existing descendant directory, emitting synthetic Create events
     * for files already present (they predate the watch, so no inotify event will ever fire for them).
     */
    private suspend fun registerNewSubtree(rootDir: String) {
        register(rootDir)
        val stack = ArrayDeque<Path>()
        stack.addLast(Path(rootDir))
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children =
                try {
                    SystemFileSystem.list(dir)
                } catch (e: kotlinx.io.IOException) {
                    logger.warn(e) { "list failed for new subtree $dir — skipping" }
                    continue
                }
            for (child in children) {
                val meta = SystemFileSystem.metadataOrNull(child) ?: continue
                if (meta.isDirectory) {
                    register(child.toString())
                    stack.addLast(child)
                } else {
                    emissions.emit(
                        DirectoryWatchEvent(dir.toString(), child.toString(), DirectoryWatchEventKind.Create),
                    )
                }
            }
        }
    }

    override suspend fun close() {
        // Wake the poll loop via the eventfd (write its 8-byte counter), then let it exit and release
        // the fds + thread.
        val counter = ByteArray(Long.SIZE_BYTES)
        counter[0] = 1
        val woke = counter.usePinned { write(shutdownFd, it.addressOf(0), counter.size.convert()) }
        if (woke < 0) {
            logger.warn {
                "eventfd shutdown write failed (errno=$errno) — relying on Job cancellation to stop the watcher"
            }
        }
        loop?.cancelAndJoin()
        close(inotifyFd)
        close(shutdownFd)
        watcherThread.close()
    }
}
