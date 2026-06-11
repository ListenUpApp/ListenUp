package com.calypsan.listenup.server.scanner.watcher

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * JVM-native recursive directory watcher over [java.nio.file.WatchService].
 *
 * Linux = inotify, Windows = ReadDirectoryChangesW (both event-driven, no
 * artificial cap — bounded only by `fs.inotify.max_user_watches`). macOS falls
 * back to the JDK's polling WatchService (~10s latency); a native FSEvents path
 * is a deferred upgrade.
 *
 * Improvements over kfswatch:
 *  - **No 63-target cap** — the whole library tree is watchable.
 *  - **New-subdir contents surfaced.** When a directory is created, it is
 *    registered AND walked, emitting synthetic [DirectoryWatchEventKind.Create]
 *    events for files already inside it — closing the race where a freshly-copied
 *    book's file lands before the watch attaches and would otherwise be invisible.
 *  - **OVERFLOW recovery.** A kernel event-buffer overrun re-emits a Create for
 *    the affected directory so its subtree is re-walked, rather than silently
 *    dropping events.
 */
internal class RecursiveDirectoryWatcher(
    private val scope: CoroutineScope,
    private val watchService: WatchService = FileSystems.getDefault().newWatchService(),
) : LowLevelDirectoryWatcher {
    private val emissions = MutableSharedFlow<DirectoryWatchEvent>(extraBufferCapacity = 256)
    override val onEventFlow: Flow<DirectoryWatchEvent> = emissions.asSharedFlow()

    private val keyToDir = ConcurrentHashMap<WatchKey, Path>()

    private val loopLock = Any()

    @Volatile private var loop: Job? = null

    /**
     * Begins watching [directory]. Registration is concurrency-safe; the single
     * consumer loop is started exactly once (guarded by [loopLock]) on the first add.
     */
    override suspend fun add(directory: String) {
        register(Path.of(directory))
        synchronized(loopLock) {
            if (loop == null) loop = startLoop()
        }
    }

    private fun register(dir: Path) {
        val key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        keyToDir[key] = dir
    }

    private fun startLoop(): Job =
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val key =
                    try {
                        runInterruptible { watchService.take() }
                    } catch (_: ClosedWatchServiceException) {
                        return@launch
                    }
                val dir = keyToDir[key]
                if (dir == null) {
                    key.reset()
                    continue
                }
                for (event in key.pollEvents()) {
                    when (event.kind()) {
                        OVERFLOW -> {
                            // Events for this dir were lost — re-emit it so the subtree re-walks.
                            emissions.emit(
                                DirectoryWatchEvent(dir.toString(), dir.toString(), DirectoryWatchEventKind.Create),
                            )
                        }

                        ENTRY_CREATE -> {
                            emitChild(dir, event, DirectoryWatchEventKind.Create)
                        }

                        ENTRY_MODIFY -> {
                            emitChild(dir, event, DirectoryWatchEventKind.Modify)
                        }

                        ENTRY_DELETE -> {
                            emitChild(dir, event, DirectoryWatchEventKind.Delete)
                        }
                    }
                }
                if (!key.reset()) keyToDir.remove(key)
            }
        }

    private suspend fun emitChild(
        dir: Path,
        event: WatchEvent<*>,
        kind: DirectoryWatchEventKind,
    ) {
        val name = event.context() as? Path ?: return
        val child = dir.resolve(name)
        emissions.emit(DirectoryWatchEvent(dir.toString(), child.toString(), kind))
        if (kind == DirectoryWatchEventKind.Create && Files.isDirectory(child)) {
            registerNewSubtree(child)
        }
    }

    /**
     * Registers [root] and every existing descendant directory, emitting synthetic
     * Create events for files already present (they predate the watch, so no native
     * event will ever fire for them).
     */
    private suspend fun registerNewSubtree(root: Path) {
        val entries =
            try {
                Files.walk(root).use { it.toList() }
            } catch (e: IOException) {
                logger.warn(e) { "walk failed for new subtree $root — skipping" }
                return
            }
        for (p in entries) {
            if (Files.isDirectory(p)) {
                runCatching { register(p) }
                    .onFailure { logger.warn(it) { "register failed for $p" } }
            } else {
                val parent = p.parent ?: continue
                emissions.emit(DirectoryWatchEvent(parent.toString(), p.toString(), DirectoryWatchEventKind.Create))
            }
        }
    }

    override suspend fun close() {
        runCatching { watchService.close() } // unblocks take() with ClosedWatchServiceException
        loop?.cancelAndJoin()
    }
}
