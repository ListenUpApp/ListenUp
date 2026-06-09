package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.scanner.watcher.WatcherSupervisor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/**
 * Minimal capability the [ScanOrchestrator] requires from a scanner.
 *
 * Restricting [ScannerBundle] to this interface rather than the concrete
 * [Scanner] class lets unit tests substitute a [FakeScanner][com.calypsan.listenup.server.scanner.ScanOrchestratorTest]
 * without pulling in real IO dependencies.
 */
internal interface ScannerResultPort {
    fun lastResult(): ScanResult?
}

private val logger = KotlinLogging.logger {}

/**
 * Top-level orchestrator that manages one [Scanner] + [ScanCoordinator] per
 * library and one [WatcherSupervisor] for all per-folder [com.calypsan.listenup.server.scanner.watcher.FolderWatcher]
 * instances.
 *
 * **Lifecycle:**
 * 1. On startup, [onLibraryAdded] is called for every existing library. This
 *    creates the scanner + coordinator pair and mounts watchers for each folder.
 * 2. [scanLibrary] triggers a full scan via the library's coordinator (single-flight).
 * 3. [scanFolder] resolves the owning library and triggers an incremental reanalysis.
 * 4. [onLibraryRemoved] tears down the scanner + coordinator and unmounts all watchers.
 * 5. [onFolderAdded] / [onFolderRemoved] adjust the watcher set.
 *
 * **Concurrency.** A [Mutex] guards [scannersByLibrary] and [coordinatorsByLibrary]
 * for lifecycle mutations (add/remove). Scan invocations delegate to
 * [ScanCoordinator] which serialises concurrent scans of the same library.
 * Concurrent scans of different libraries are allowed and run in parallel.
 *
 * @param scannerFactory creates a [Scanner] for the given [Library]. Must also
 *   expose a [ScanCoordinator] — callers typically return a pre-paired object.
 *   Separated from the constructor to enable testing without real scanner deps.
 * @param watcherSupervisor manages per-folder watcher instances.
 */
internal class ScanOrchestrator(
    private val scannerFactory: (Library) -> ScannerBundle,
    private val watcherSupervisor: WatcherSupervisorPort,
) {
    private val mutex = Mutex()
    private val bundlesByLibrary = mutableMapOf<LibraryId, ScannerBundle>()

    // Folder-to-library reverse index for scanFolder lookups.
    private val libraryByFolder = mutableMapOf<FolderId, LibraryId>()

    /** True if any library currently has a scan in flight. */
    suspend fun isScanning(): Boolean = mutex.withLock { bundlesByLibrary.values.any { it.coordinator.isScanning() } }

    /**
     * Registers [library] with the orchestrator: creates a [Scanner] +
     * [ScanCoordinator] pair, registers the folder-to-library index, and mounts
     * file-system watchers for each folder.
     *
     * Called at server startup for every existing library and after a
     * successful `createLibrary` admin RPC.
     */
    suspend fun onLibraryAdded(library: Library) {
        mutex.withLock {
            val bundle = scannerFactory(library)
            bundlesByLibrary[library.id] = bundle
            for (folder in library.folders) {
                libraryByFolder[folder.id] = library.id
            }
        }
        for (folder in library.folders) {
            watcherSupervisor.mount(library.id, folder) { libId, path ->
                onFileChanged(libId, path)
            }
        }
        logger.info {
            "Library registered: id=${library.id.value} name='${library.name}' folders=${library.folders.size}"
        }
    }

    /**
     * Removes [libraryId] from the orchestrator: tears down the scanner bundle
     * and unmounts all associated file-system watchers.
     *
     * Called after a successful `deleteLibrary` admin RPC.
     */
    suspend fun onLibraryRemoved(libraryId: LibraryId) {
        mutex.withLock {
            bundlesByLibrary.remove(libraryId)
            libraryByFolder.entries.removeIf { (_, libId) -> libId == libraryId }
        }
        watcherSupervisor.unmountAllForLibrary(libraryId)
        logger.info { "Library removed: id=${libraryId.value}" }
    }

    /**
     * Mounts a new watcher for [folder] under [libraryId].
     *
     * Called after a successful `addFolder` admin RPC.
     */
    suspend fun onFolderAdded(
        libraryId: LibraryId,
        folder: LibraryFolderRef,
    ) {
        mutex.withLock {
            libraryByFolder[folder.id] = libraryId
        }
        watcherSupervisor.mount(libraryId, folder) { libId, path ->
            onFileChanged(libId, path)
        }
        logger.info {
            "Folder registered: library=${libraryId.value} folder=${folder.id.value} path=${folder.rootPath}"
        }
    }

    /**
     * Unmounts the watcher for [folderId].
     *
     * Called after a successful `removeFolder` admin RPC.
     */
    suspend fun onFolderRemoved(folderId: FolderId) {
        mutex.withLock {
            libraryByFolder.remove(folderId)
        }
        watcherSupervisor.unmount(folderId)
        logger.info { "Folder removed: folder=${folderId.value}" }
    }

    /**
     * Triggers a full scan of [libraryId] via the library's [ScanCoordinator].
     *
     * Returns [LibraryError.NotFound] when [libraryId] is not registered.
     * Returns [ScanError.AlreadyRunning] when a scan is already in flight for
     * that library (the coordinator's single-flight contract).
     */
    suspend fun scanLibrary(libraryId: LibraryId): AppResult<ScanResult> {
        val bundle =
            mutex.withLock { bundlesByLibrary[libraryId] }
                ?: return AppResult.Failure(LibraryError.NotFound())
        return bundle.coordinator.scanFull()
    }

    /**
     * Fire-and-forget variant of [scanLibrary]: kicks the full scan off on the library's
     * coordinator scope and returns immediately ("202 Accepted"). Returns
     * [LibraryError.NotFound] when the library isn't registered and
     * [ScanError.AlreadyRunning] when a scan is already in flight — both surfaced
     * synchronously. The scan itself runs in the background and streams progress over SSE.
     *
     * This is what the admin/wizard `LibraryAdminService.scanLibrary` trigger uses, so the
     * RPC/HTTP call doesn't block for the entire walk. [scanLibrary] (blocking, returns the
     * [ScanResult]) is retained for the scanner vertical's synchronous summary endpoint.
     */
    suspend fun scanLibraryAsync(libraryId: LibraryId): AppResult<Unit> {
        val bundle =
            mutex.withLock { bundlesByLibrary[libraryId] }
                ?: return AppResult.Failure(LibraryError.NotFound())
        return bundle.coordinator.scanFullAsync()
    }

    /**
     * Triggers an incremental re-analysis of the subtree under [folderId].
     *
     * Returns [LibraryError.FolderNotFound] when [folderId] is not registered.
     */
    fun scanFolder(folderId: FolderId) {
        val libraryId =
            libraryByFolder[folderId] ?: run {
                logger.warn { "scanFolder: folderId=${folderId.value} not registered — ignoring" }
                return
            }
        val bundle = bundlesByLibrary[libraryId] ?: return
        val folderPath =
            bundle.library.folders
                .firstOrNull { it.id == folderId }
                ?.rootPath ?: return
        bundle.coordinator.reanalyze(Path.of(folderPath))
    }

    /**
     * Returns the most recent [ScanResult] for [libraryId], or null when no
     * scan has completed yet or the library is not registered.
     */
    fun lastResult(libraryId: LibraryId): ScanResult? = bundlesByLibrary[libraryId]?.scanner?.lastResult()

    /**
     * Returns the most recent [ScanResult] across all libraries. Returns the
     * result from the library with the highest timestamp, or null when no scan
     * has run yet.
     */
    fun lastResultAny(): ScanResult? =
        bundlesByLibrary.values
            .mapNotNull { it.scanner.lastResult() }
            .maxByOrNull { it.durationMs }

    private fun onFileChanged(
        libraryId: LibraryId,
        subtreePath: Path,
    ) {
        val bundle = bundlesByLibrary[libraryId] ?: return
        // Find the owning folder and compute a book-root within that folder.
        val owningFolder =
            bundle.library.folders.firstOrNull { folder ->
                subtreePath.startsWith(Path.of(folder.rootPath))
            }
        val bookRoot = owningFolder?.let { subtreePath } ?: subtreePath
        bundle.coordinator.reanalyze(bookRoot)
    }
}

/**
 * A scanner and its paired [ScanCoordinator]. The orchestrator creates one
 * bundle per library.
 *
 * [scanner] is typed as [ScannerResultPort] so the orchestrator can call
 * [ScannerResultPort.lastResult] without coupling to the concrete [Scanner]
 * class — tests inject a fake instead.
 */
internal data class ScannerBundle(
    val library: Library,
    val scanner: ScannerResultPort,
    val coordinator: ScanCoordinator,
)

/**
 * Port interface allowing [WatcherSupervisor] to be substituted with a fake
 * in unit tests. The real binding connects to the concrete [WatcherSupervisor].
 */
internal interface WatcherSupervisorPort {
    suspend fun mount(
        libraryId: LibraryId,
        folder: LibraryFolderRef,
        onEvent: suspend (LibraryId, Path) -> Unit,
    )

    suspend fun unmount(folderId: FolderId)

    suspend fun unmountAllForLibrary(libraryId: LibraryId)

    suspend fun unmountAll()
}

/** Adapts the concrete [WatcherSupervisor] to [WatcherSupervisorPort]. */
internal fun WatcherSupervisor.asPort(): WatcherSupervisorPort =
    object : WatcherSupervisorPort {
        override suspend fun mount(
            libraryId: LibraryId,
            folder: LibraryFolderRef,
            onEvent: suspend (LibraryId, Path) -> Unit,
        ) = this@asPort.mount(libraryId, folder, onEvent)

        override suspend fun unmount(folderId: FolderId) = this@asPort.unmount(folderId)

        override suspend fun unmountAllForLibrary(libraryId: LibraryId) = this@asPort.unmountAllForLibrary(libraryId)

        override suspend fun unmountAll() = this@asPort.unmountAll()
    }
