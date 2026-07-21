package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for single-library administration.
 *
 * In the single-library model there is exactly one library; all operations target
 * it without requiring a library id. Manages the library's root folders (add,
 * remove, scan) and provides onboarding helpers ([getSetupStatus], [browseFilesystem]).
 *
 * The library is server-wide (cross-user). All folder-mutating methods
 * ([addFolder], [removeFolder], [scanFolder], [scanLibrary]) and the
 * filesystem-exposing [browseFilesystem] require ROOT/ADMIN; non-admins receive
 * [com.calypsan.listenup.api.error.AuthError.PermissionDenied]. The read-only
 * [getLibrary] and [getSetupStatus] stay open to any authenticated caller so
 * members can browse content and the onboarding wizard can run before an admin exists.
 *
 * REST mirrors are defined in
 * `LibraryResources`.
 *
 * Two surface categories:
 * - **Observation + setup** — [getLibrary], [getSetupStatus], [browseFilesystem]
 *   are safe to call repeatedly; they read state only.
 * - **Folder lifecycle + scan** — [addFolder], [removeFolder],
 *   [scanLibrary], [scanFolder] mutate server state and should be called
 *   once per user intent.
 */
@Rpc
interface LibraryAdminService {
    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Returns THE library (single-library model) with its [Library.folders] populated.
     *
     * In the single-library model there is exactly one library; this resolves it without
     * an id. Open to any authenticated caller.
     */
    suspend fun getLibrary(): AppResult<Library>

    // ── Setup / onboarding ───────────────────────────────────────────────────

    /**
     * Returns the current setup status — whether the server needs onboarding
     * ([SetupStatus.needsSetup] = `true` when THE library has no folders yet).
     *
     * Clients use this to decide whether to show the onboarding
     * wizard on first launch.
     *
     * Open to any authenticated caller — drives onboarding before an admin exists.
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
     * Admin-only — non-admins receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied].
     */
    suspend fun browseFilesystem(path: String): AppResult<List<DirectoryEntry>>

    // ── Folder lifecycle ─────────────────────────────────────────────────────

    /**
     * Registers a new folder at [path] under THE library and returns the created [LibraryFolder].
     *
     * Validates the path (must exist and be a readable directory). Admin-only —
     * non-admins receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied].
     */
    suspend fun addFolder(path: String): AppResult<LibraryFolder>

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
     * Admin-only — non-admins receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied].
     */
    suspend fun removeFolder(folderId: FolderId): AppResult<Unit>

    // ── Scan triggers ────────────────────────────────────────────────────────

    /**
     * Triggers a full scan of THE library. Fire-and-forget: returns once the scan is
     * accepted; progress streams over `ScannerService.observeProgress` (RPC). Admin-only.
     */
    suspend fun scanLibrary(): AppResult<Unit>

    /**
     * Triggers an incremental scan of the folder identified by [folderId].
     *
     * Delegates to `ScanOrchestrator.scanFolder` which resolves the parent
     * library and runs `Scanner.runIncremental(subtreePath)`.
     *
     * Returns [com.calypsan.listenup.api.error.LibraryError.FolderNotFound] when
     * no folder with the given id exists.
     *
     * Admin-only — non-admins receive [com.calypsan.listenup.api.error.AuthError.PermissionDenied].
     */
    suspend fun scanFolder(folderId: FolderId): AppResult<Unit>
}
