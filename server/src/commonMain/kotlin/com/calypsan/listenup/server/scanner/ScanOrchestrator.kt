package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/**
 * Minimal capability the [ScanOrchestrator] requires from a scanner.
 *
 * Restricting [ScannerBundle] to this interface rather than the concrete
 * [Scanner] class lets unit tests substitute a [FakeScanner][com.calypsan.listenup.server.scanner.ScanOrchestratorTest]
 * without pulling in real IO dependencies.
 */
internal interface ScannerResultPort {
    fun lastResult(): ScanResult?

    /**
     * Marks this scanner's bundle as superseded by a later one (a folder was added/removed and the
     * orchestrator rebuilt the bundle). A scan still in flight on the old bundle walked a stale folder
     * set; once superseded it must NOT publish its result — a stale full-scan result would
     * tombstone-sweep the newly-added folder's books, which are absent from its seen set (A8).
     */
    fun markSuperseded()
}

private val logger = loggerFor<ScanOrchestrator>()

/**
 * Top-level orchestrator that manages the single [Scanner] + [ScanCoordinator] bundle
 * for the one library and one [WatcherSupervisor] for all per-folder
 * [com.calypsan.listenup.server.scanner.watcher.FolderWatcher] instances.
 *
 * **Lifecycle:**
 * 1. On startup, [onLibraryAdded] is called for the configured library. This
 *    creates the scanner + coordinator pair and mounts watchers for each folder.
 * 2. [scanLibrary] triggers a full scan via the coordinator (single-flight).
 * 3. [scanFolder] triggers an incremental reanalysis for the given folder.
 * 4. [onFolderAdded] / [onFolderRemoved] rebuild the scanner bundle with the updated folder
 *    list and mount/unmount only the affected watcher.
 *
 * **One library, many folders.** ListenUp is a single-library product. A second call
 * to [onLibraryAdded] replaces the existing bundle — it does not create a second one.
 *
 * **Concurrency.** A [Mutex] guards [bundle] for lifecycle mutations (add/remove).
 * Scan invocations delegate to [ScanCoordinator] which serialises concurrent scans.
 *
 * @param scannerFactory creates a [ScannerBundle] for the given [Library]. Separated
 *   from the constructor to enable testing without real scanner deps.
 * @param watcherSupervisor manages per-folder watcher instances.
 * @param watchEnabled when `false`, [onLibraryAdded] and [onFolderAdded] skip
 *   mounting real-time file-system watchers — the library is still registered
 *   for scanning, only the live `WatchService` is suppressed. Defaults to `true`
 *   (production behaviour). Tests that write fixture files into the library root
 *   after boot disable it so the watcher can't race the seed (gated by
 *   `scanner.watchEnabled`, mirroring the `mdns.enabled` precedent).
 */
internal class ScanOrchestrator(
    private val scannerFactory: (Library) -> ScannerBundle,
    private val watcherSupervisor: WatcherSupervisorPort,
    private val watchEnabled: Boolean = true,
) {
    private val mutex = Mutex()

    // The single library's scanner bundle (or null before configuration). One library, many folders.
    private var bundle: ScannerBundle? = null

    /** True if the library currently has a scan in flight. */
    suspend fun isScanning(): Boolean = mutex.withLock { bundle?.coordinator?.isScanning() == true }

    /**
     * Registers [library] with the orchestrator: creates a [Scanner] +
     * [ScanCoordinator] pair and mounts file-system watchers for each folder.
     *
     * Called at server startup (bootstrap) to register the singleton library.
     * A second call replaces the existing bundle — there is only ever one library
     * active at a time.
     */
    suspend fun onLibraryAdded(library: Library) {
        val newBundle = scannerFactory(library)
        mutex.withLock { bundle = newBundle }
        if (watchEnabled) {
            for (folder in library.folders) {
                watcherSupervisor.mount(library.id, folder) { libId, path -> onFileChanged(libId, path) }
            }
        }
        logger.info {
            "Library registered: id=${library.id.value} name='${library.name}' folders=${library.folders.size}" +
                if (watchEnabled) "" else " (real-time watching disabled)"
        }
    }

    /**
     * Rebuilds the scanner bundle with [folder] appended to the library's folder list,
     * then mounts a watcher for the new folder only.
     *
     * Rebuilding (rather than patching the snapshot) is required because the [Scanner]
     * captures [Library.folders] at construction time as its walk roots. A snapshot-only
     * patch would leave the Scanner's internal roots stale, so a subsequent full scan
     * would not walk the new folder.
     *
     * Called after a successful `addFolder` admin RPC.
     */
    suspend fun onFolderAdded(
        libraryId: LibraryId,
        folder: LibraryFolderRef,
    ) {
        val stale =
            mutex.withLock {
                val current = bundle
                if (current != null && current.library.id == libraryId) {
                    val updatedLibrary = current.library.copy(folders = current.library.folders + folder)
                    // Supersede the old scanner BEFORE swapping so a scan already in flight on it drops
                    // its stale result instead of sweeping the new folder's books (A8).
                    current.scanner.markSuperseded()
                    // Rebuild so the Scanner's captured folder list includes the new folder
                    // (a full scan walks the Scanner's roots; a snapshot-only patch wouldn't reach it).
                    bundle = scannerFactory(updatedLibrary)
                    current
                } else {
                    null
                }
            }
        // Close the old coordinator outside the lock — closes the incremental channel,
        // letting its worker coroutine drain and exit without cancelling the shared scope.
        stale?.coordinator?.close()
        if (watchEnabled) {
            watcherSupervisor.mount(libraryId, folder) { libId, path -> onFileChanged(libId, path) }
        }
        logger.info {
            "Folder registered: library=${libraryId.value} folder=${folder.id.value} path=${folder.rootPath}" +
                if (watchEnabled) "" else " (real-time watching disabled)"
        }
    }

    /**
     * Rebuilds the scanner bundle with [folderId] removed from the library's folder list,
     * then unmounts the watcher for that folder.
     *
     * Rebuilding ensures a subsequent full scan does not walk the removed folder's path
     * (the [Scanner] captures its walk roots at construction time).
     *
     * Called after a successful `removeFolder` admin RPC.
     */
    suspend fun onFolderRemoved(folderId: FolderId) {
        val stale =
            mutex.withLock {
                bundle?.let { current ->
                    val updatedLibrary =
                        current.library.copy(
                            folders = current.library.folders.filterNot { it.id == folderId },
                        )
                    // Supersede the old scanner before swapping so an in-flight scan drops its stale
                    // result rather than sweeping against a folder set that no longer applies (A8).
                    current.scanner.markSuperseded()
                    bundle = scannerFactory(updatedLibrary)
                    current
                }
            }
        // Close the old coordinator outside the lock — closes the incremental channel,
        // letting its worker coroutine drain and exit without cancelling the shared scope.
        stale?.coordinator?.close()
        watcherSupervisor.unmount(folderId)
        logger.info { "Folder removed: folder=${folderId.value}" }
    }

    /**
     * Triggers a full scan of [libraryId] via the library's [ScanCoordinator].
     *
     * Returns [LibraryError.NotFound] when [libraryId] is not registered.
     * Returns [com.calypsan.listenup.api.error.ScanError.AlreadyRunning] when a scan is already in flight for
     * that library (the coordinator's single-flight contract).
     */
    suspend fun scanLibrary(libraryId: LibraryId): AppResult<ScanResult> {
        val active =
            mutex.withLock { bundle?.takeIf { it.library.id == libraryId } }
                ?: return AppResult.Failure(LibraryError.NotFound())
        return active.coordinator.scanFull()
    }

    /**
     * Fire-and-forget variant of [scanLibrary]: kicks the full scan off on the library's
     * coordinator scope and returns immediately ("202 Accepted"). Returns
     * [LibraryError.NotFound] when the library isn't registered and
     * [com.calypsan.listenup.api.error.ScanError.AlreadyRunning] when a scan is already in flight — both surfaced
     * synchronously. The scan itself runs in the background and streams progress over SSE.
     *
     * This is what the admin/wizard `LibraryAdminService.scanLibrary` trigger uses, so the
     * RPC/HTTP call doesn't block for the entire walk. [scanLibrary] (blocking, returns the
     * [ScanResult]) is retained for the scanner vertical's synchronous summary endpoint.
     */
    suspend fun scanLibraryAsync(libraryId: LibraryId): AppResult<Unit> {
        val active =
            mutex.withLock { bundle?.takeIf { it.library.id == libraryId } }
                ?: return AppResult.Failure(LibraryError.NotFound())
        return active.coordinator.scanFullAsync()
    }

    /**
     * Triggers an incremental re-analysis of the subtree under [folderId].
     *
     * Returns silently when [folderId] is not found in the registered library's folders.
     */
    fun scanFolder(folderId: FolderId) {
        val active = bundle ?: return
        val folderPath =
            active.library.folders
                .firstOrNull { it.id == folderId }
                ?.rootPath ?: run {
                logger.warn { "scanFolder: folderId=${folderId.value} not registered — ignoring" }
                return
            }
        active.coordinator.reanalyze(Path(folderPath))
    }

    /**
     * Returns the most recent [ScanResult] for [libraryId], or null when no
     * scan has completed yet or the library is not registered.
     */
    fun lastResult(libraryId: LibraryId): ScanResult? =
        bundle?.takeIf { it.library.id == libraryId }?.scanner?.lastResult()

    /** The registered library id, or null before the library is configured. */
    fun registeredLibraryId(): LibraryId? = bundle?.library?.id

    private fun onFileChanged(
        libraryId: LibraryId,
        subtreePath: Path,
    ) {
        val active = bundle?.takeIf { it.library.id == libraryId } ?: return
        // Ignore events outside every registered folder — a watcher can surface paths (moves,
        // sibling mounts) that belong to no configured folder, and reanalyzing them would walk a
        // subtree the library doesn't own.
        val owns =
            active.library.folders.any { folder ->
                folder.rootPath?.let { subtreePath.isUnder(Path(it)) } ?: false
            }
        if (!owns) {
            logger.debug { "ignoring filesystem event outside all registered folders: $subtreePath" }
            return
        }
        active.coordinator.reanalyze(subtreePath)
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
