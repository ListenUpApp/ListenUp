package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.CreateLibraryRequest
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for library and folder lifecycle administration.
 *
 * Manages the full lifecycle of libraries and their root folders: create, rename,
 * delete, add folder, remove folder, and trigger scans. Also provides the
 * onboarding helpers ([getSetupStatus], [browseFilesystem]) that replace the legacy
 * `SetupApiContract` REST surface.
 *
 * Libraries are server-wide (cross-user) today. Every method carries a
 * `// TODO: gate by user permissions when Multi-user lands` marker — per-user
 * permission enforcement is deferred to the Multi-user phase.
 *
 * REST mirrors are defined in
 * [com.calypsan.listenup.api.resources.LibraryResources].
 *
 * Two surface categories:
 * - **Observation + setup** — [listLibraries], [getLibrary], [getSetupStatus],
 *   [browseFilesystem] are safe to call repeatedly; they read state only.
 * - **Lifecycle + scan** — [createLibrary], [renameLibrary], [deleteLibrary],
 *   [addFolder], [removeFolder], [scanLibrary], [scanFolder] mutate server state
 *   and should be called once per user intent.
 */
@Rpc
interface LibraryAdminService {
    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Returns all non-deleted libraries known to the server, each with its
     * [Library.folders] list populated.
     *
     * The sync handler ([LibrarySyncDomainHandler]) is the primary source of
     * truth for client-side Room; this method is a convenience for admin UIs
     * and post-create confirmation flows.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun listLibraries(): AppResult<List<Library>>

    /**
     * Returns the library identified by [id], or `null` inside [AppResult.Success]
     * when no library with that id exists.
     *
     * The server does not return [com.calypsan.listenup.api.error.LibraryError.NotFound]
     * here — callers check the `null` case directly, matching the convention used
     * by [BookService.getBook].
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun getLibrary(id: LibraryId): AppResult<Library?>

    // ── Setup / onboarding ───────────────────────────────────────────────────

    /**
     * Returns the current setup status — whether the server needs onboarding
     * ([SetupStatus.needsSetup] = `true` when library count is zero) and the
     * total count of non-deleted libraries.
     *
     * Migrates the legacy `SetupApiContract.getLibraryStatus` capability into the
     * RPC surface. Clients use this to decide whether to show the onboarding
     * wizard on first launch.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun getSetupStatus(): AppResult<SetupStatus>

    /**
     * Lists the immediate sub-directories of [path] on the server filesystem.
     *
     * Used by the onboarding wizard and folder-picker UI to browse the server's
     * filesystem for root folders. Each [DirectoryEntry] includes [DirectoryEntry.hasChildren]
     * so clients can show an expand affordance without a recursive call.
     *
     * Returns an empty list when [path] has no sub-directories. Returns
     * [com.calypsan.listenup.api.error.LibraryError.InvalidPath] when [path] does
     * not exist or is not readable.
     *
     * Migrates the legacy `SetupApiContract.listDirectory` capability into the
     * RPC surface.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun browseFilesystem(path: String): AppResult<List<DirectoryEntry>>

    // ── Library lifecycle ────────────────────────────────────────────────────

    /**
     * Creates a new library with the given [request] and returns the created
     * [Library] aggregate with folders populated.
     *
     * The server validates every path in [CreateLibraryRequest.folderPaths]:
     * - [com.calypsan.listenup.api.error.LibraryError.InvalidPath] when a path
     *   does not exist or is not readable.
     * - [com.calypsan.listenup.api.error.LibraryError.DuplicateFolder] when a
     *   path is already registered under another library.
     *
     * On success the server emits SSE events for the new library and each of its
     * folders so connected clients' Room databases update reactively.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun createLibrary(request: CreateLibraryRequest): AppResult<Library>

    /**
     * Renames the library identified by [id] to [name] and returns the updated
     * [Library] aggregate.
     *
     * Returns [com.calypsan.listenup.api.error.LibraryError.NotFound] when no
     * library with the given id exists.
     *
     * On success the server emits an SSE event for the updated library.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun renameLibrary(
        id: LibraryId,
        name: String,
    ): AppResult<Library>

    /**
     * Deletes the library identified by [id] and cascade-deletes all of its
     * folders and books atomically inside a single transaction.
     *
     * Cascade semantics (all performed inside one `suspendTransaction`):
     * 1. Soft-delete every book belonging to this library (SSE event per book).
     * 2. Soft-delete every `library_folder` row for this library.
     * 3. Soft-delete the library row.
     * Outside the transaction: `ScanOrchestrator.onLibraryRemoved(id)` tears down
     * the per-library scanner, coordinator, and folder watchers.
     *
     * Returns [com.calypsan.listenup.api.error.LibraryError.NotFound] when no
     * library with the given id exists.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun deleteLibrary(id: LibraryId): AppResult<Unit>

    // ── Folder lifecycle ─────────────────────────────────────────────────────

    /**
     * Registers a new folder at [path] under the library identified by [libraryId]
     * and returns the created [LibraryFolder].
     *
     * Validates the path:
     * - [com.calypsan.listenup.api.error.LibraryError.InvalidPath] when [path]
     *   does not exist or is not readable.
     * - [com.calypsan.listenup.api.error.LibraryError.DuplicateFolder] when
     *   [path] is already registered under another library.
     *
     * On success calls `ScanOrchestrator.onFolderAdded` to mount a watcher for
     * the new folder and emits an SSE event.
     *
     * Returns [com.calypsan.listenup.api.error.LibraryError.NotFound] when no
     * library with [libraryId] exists.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun addFolder(
        libraryId: LibraryId,
        path: String,
    ): AppResult<LibraryFolder>

    /**
     * Removes the folder identified by [folderId] and cascade-deletes its books.
     *
     * Cascade semantics (all performed inside one `suspendTransaction`):
     * 1. Soft-delete every book belonging to this folder (SSE event per book).
     * 2. Soft-delete the folder row.
     * Outside the transaction: `ScanOrchestrator.onFolderRemoved(folderId)` unmounts
     * the folder's watcher.
     *
     * Returns [com.calypsan.listenup.api.error.LibraryError.FolderNotFound] when no
     * folder with the given id exists.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun removeFolder(folderId: FolderId): AppResult<Unit>

    // ── Scan triggers ────────────────────────────────────────────────────────

    /**
     * Triggers a full scan of the library identified by [libraryId].
     *
     * The scan walks all registered folders, groups audio files into books,
     * analyzes metadata, and diffs against the current library snapshot. Uses
     * `ScanOrchestrator.scanLibrary` which enforces a per-library single-flight
     * guarantee — concurrent scan requests for the same library collapse to one
     * in-flight scan.
     *
     * Returns [com.calypsan.listenup.api.error.LibraryError.NotFound] when no
     * library with the given id exists.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun scanLibrary(libraryId: LibraryId): AppResult<Unit>

    /**
     * Triggers an incremental scan of the folder identified by [folderId].
     *
     * Delegates to `ScanOrchestrator.scanFolder` which resolves the parent
     * library and runs `Scanner.runIncremental(subtreePath)`.
     *
     * Returns [com.calypsan.listenup.api.error.LibraryError.FolderNotFound] when
     * no folder with the given id exists.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun scanFolder(folderId: FolderId): AppResult<Unit>
}
