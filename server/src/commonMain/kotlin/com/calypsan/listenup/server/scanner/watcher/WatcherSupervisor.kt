package com.calypsan.listenup.server.scanner.watcher

import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

private val logger = KotlinLogging.logger {}

/**
 * Manages the lifecycle of [FolderWatcher] instances — one per registered
 * library folder. The supervisor is the single point of control for mounting
 * and unmounting watchers as library folders are created, modified, or removed.
 *
 * Internal state is guarded by a [Mutex] so concurrent mount/unmount calls
 * from [ScanOrchestrator][com.calypsan.listenup.server.scanner.ScanOrchestrator]
 * do not race.
 *
 * @param folderWatcherFactory creates and starts a watcher for one folder.
 *   Returns a close handle (a suspending no-arg lambda) so the supervisor can
 *   stop the watcher without coupling to the concrete [FolderWatcher] type —
 *   tests inject a [FakeFolderWatcher][com.calypsan.listenup.server.scanner.watcher.FakeFolderWatcher]
 *   via this seam.
 *   The factory's [onEvent] callback receives the subtree [Path] that changed.
 */
internal class WatcherSupervisor(
    private val folderWatcherFactory: suspend (
        folder: LibraryFolderRef,
        onEvent: suspend (Path) -> Unit,
    ) -> WatcherHandle,
) {
    private val mutex = Mutex()

    // Active watcher per folder id.
    private val watchersByFolder = mutableMapOf<FolderId, WatcherHandle>()

    // Which folders belong to each library — used by unmountAllForLibrary.
    private val foldersByLibrary = mutableMapOf<LibraryId, MutableSet<FolderId>>()

    /**
     * Creates and starts a watcher for [folder] under [libraryId].
     *
     * When a file-change event fires, [onEvent] is invoked with the owning
     * [libraryId] and the affected subtree [Path]. The supervisor routes the
     * event through the factory so the [ScanOrchestrator] can trigger the
     * correct per-library incremental scan.
     *
     * Idempotent: if a watcher for [folder.id] already exists, this is a no-op.
     */
    suspend fun mount(
        libraryId: LibraryId,
        folder: LibraryFolderRef,
        onEvent: suspend (LibraryId, Path) -> Unit,
    ) = mutex.withLock {
        if (watchersByFolder.containsKey(folder.id)) {
            logger.debug { "Watcher already mounted for folder=${folder.id.value}; skipping" }
            return@withLock
        }
        val handle =
            folderWatcherFactory(folder) { path ->
                onEvent(libraryId, path)
            }
        watchersByFolder[folder.id] = handle
        foldersByLibrary.getOrPut(libraryId) { mutableSetOf() } += folder.id
        logger.info {
            "Mounted watcher for library=${libraryId.value} folder=${folder.id.value} path=${folder.rootPath}"
        }
    }

    /**
     * Stops and removes the watcher for [folderId].
     *
     * No-op when no watcher is registered for [folderId].
     */
    suspend fun unmount(folderId: FolderId) =
        mutex.withLock {
            val handle = watchersByFolder.remove(folderId) ?: return@withLock
            handle.close()
            foldersByLibrary.values.forEach { it -= folderId }
            logger.info { "Unmounted watcher for folder=${folderId.value}" }
        }

    /**
     * Stops and removes all watchers registered under [libraryId].
     *
     * Called when a library is deleted or on server shutdown.
     */
    suspend fun unmountAllForLibrary(libraryId: LibraryId) =
        mutex.withLock {
            val folderIds = foldersByLibrary.remove(libraryId) ?: return@withLock
            for (folderId in folderIds) {
                val handle = watchersByFolder.remove(folderId) ?: continue
                handle.close()
            }
            logger.info { "Unmounted ${folderIds.size} watcher(s) for library=${libraryId.value}" }
        }

    /**
     * Stops and removes EVERY active watcher across all libraries. Called on server
     * shutdown so the native WatchService handles are released deterministically — it *closes*
     * each handle (releasing the native FS handle) rather than joining its event loop,
     * which would block disposal. Idempotent: a second call is a no-op.
     */
    suspend fun unmountAll() =
        mutex.withLock {
            if (watchersByFolder.isEmpty()) return@withLock
            val count = watchersByFolder.size
            for ((_, handle) in watchersByFolder) {
                handle.close()
            }
            watchersByFolder.clear()
            foldersByLibrary.clear()
            logger.info { "Unmounted $count watcher(s) on shutdown" }
        }
}

/**
 * A minimal handle to an active [FolderWatcher] — just a [close] capability
 * that the [WatcherSupervisor] uses to stop the watcher without depending on
 * the concrete [FolderWatcher] type.
 */
internal interface WatcherHandle {
    suspend fun close()
}
