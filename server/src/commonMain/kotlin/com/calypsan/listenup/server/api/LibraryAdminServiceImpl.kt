package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = loggerFor<LibraryAdminServiceImpl>()

/**
 * Server-side implementation of [LibraryAdminService].
 *
 * Manages the single library and its root folders through the syncable-substrate
 * repositories. Folder mutations (add/remove, scan) run through [suspendTransaction]
 * for atomicity, then call out to [scanOrchestrator] **after** the transaction commits
 * so the orchestrator's watcher/scanner state reflects the committed DB state.
 *
 * Library structure is ADMIN territory. Every mutating op — add/remove folder,
 * scan triggers — and the filesystem-exposing [browseFilesystem] are gated through
 * [requireAdmin] (ROOT/ADMIN pass; everyone else gets [AuthError.PermissionDenied]).
 * The read-only [getLibrary] and [getSetupStatus] stay open to any authenticated
 * caller: members need to resolve the library for normal content browsing, and the
 * setup status drives first-launch onboarding.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal;
 * the Koin singleton carries an unscoped placeholder ([PrincipalProvider.None]) that
 * yields no principal, so an unscoped mutating call is denied rather than silently
 * running unauthenticated.
 */
internal class LibraryAdminServiceImpl(
    private val libraryRepository: LibraryRepository,
    private val libraryFolderRepository: LibraryFolderRepository,
    private val bookRepository: BookRepository,
    private val scanOrchestrator: ScanOrchestrator,
    private val libraryRegistry: LibraryRegistry,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : LibraryAdminService {
    // ── Observation ──────────────────────────────────────────────────────────

    override suspend fun getLibrary(): AppResult<Library> {
        // Open to any authenticated caller: single-library resolution without an id.
        val id = libraryRegistry.currentLibrary()
        val page = libraryRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val payload =
            page.items.firstOrNull { it.id == id.value && it.deletedAt == null }
                ?: return AppResult.Failure(LibraryError.NotFound())
        return AppResult.Success(payload.toLibraryWithFolders())
    }

    override suspend fun getSetupStatus(): AppResult<SetupStatus> {
        // Open to any authenticated caller: drives first-launch onboarding before any admin exists.
        // needsSetup is true when THE library has no folders (not when there are no libraries —
        // the singleton library always exists; what matters for onboarding is whether it has paths).
        val id = libraryRegistry.currentLibrary()
        val folderPage = libraryFolderRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val folderCount = folderPage.items.count { it.libraryId == id.value && it.deletedAt == null }
        return AppResult.Success(
            SetupStatus(needsSetup = folderCount == 0, isScanning = scanOrchestrator.isScanning()),
        )
    }

    override suspend fun browseFilesystem(path: String): AppResult<List<DirectoryEntry>> {
        // Admin-only: walking the server filesystem must never be exposed to members.
        requireAdmin()?.let { return AppResult.Failure(it) }
        val dir = Path(path)
        return try {
            if (!SystemFileSystem.exists(dir)) {
                return AppResult.Failure(LibraryError.InvalidPath(debugInfo = "Path does not exist: $path"))
            }
            val metadata = SystemFileSystem.metadataOrNull(dir)
            if (metadata == null || !metadata.isDirectory) {
                return AppResult.Failure(LibraryError.InvalidPath(debugInfo = "Not a directory: $path"))
            }
            val children =
                SystemFileSystem
                    .list(dir)
                    .filter { child ->
                        val childMeta = SystemFileSystem.metadataOrNull(child)
                        childMeta != null && childMeta.isDirectory
                    }.map { child ->
                        val childPath = child.toString()
                        val name = child.name
                        val grandchildren =
                            runCatching { SystemFileSystem.list(child) }.getOrDefault(emptyList())
                        val itemCount = grandchildren.size
                        val hasChildren =
                            grandchildren.any { grandchild ->
                                SystemFileSystem.metadataOrNull(grandchild)?.isDirectory == true
                            }
                        DirectoryEntry(name = name, path = childPath, hasChildren = hasChildren, itemCount = itemCount)
                    }.sortedBy { it.name }
            AppResult.Success(children)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(LibraryError.InvalidPath(debugInfo = "Error reading path $path: ${e.message}"))
        }
    }

    // ── Folder lifecycle ─────────────────────────────────────────────────────

    override suspend fun addFolder(path: String): AppResult<LibraryFolder> {
        requireAdmin()?.let { return AppResult.Failure(it) }
        return addFolderTo(libraryRegistry.currentLibrary(), path)
    }

    /** Validates [path] and creates a new folder row under [libraryId]. Admin gate and library-existence
     * check must be performed by the caller before invoking this helper. */
    private suspend fun addFolderTo(
        libraryId: LibraryId,
        path: String,
    ): AppResult<LibraryFolder> {
        val dir = Path(path)
        if (!SystemFileSystem.exists(dir) || SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) {
            return AppResult.Failure(
                LibraryError.InvalidPath(debugInfo = "Path does not exist or is not a directory: $path"),
            )
        }
        val duplicateCheck = checkForDuplicateFolders(listOf(path))
        if (duplicateCheck != null) return AppResult.Failure((duplicateCheck as AppResult.Failure).error)

        val now = clock.now().toEpochMilliseconds()
        // Remove+re-add of the SAME path: REUSE the soft-deleted folder's stable id (exact-path
        // match only) so the re-added folder keeps its folder_id and its tombstoned books revive
        // under their original UUIDs — instead of minting a fresh id that re-adds every book as a
        // NEW UUID and strands every client's saved references (playback position, shelves, 404s).
        val reusedFolder = libraryFolderRepository.findDeletedByRootPath(path)
        val folderId = reusedFolder?.let { FolderId(it.id) } ?: FolderId(Uuid.random().toString())
        val folderPayload =
            LibraryFolderSyncPayload(
                id = folderId.value,
                libraryId = libraryId.value,
                rootPath = path,
                // On reuse the substrate's UPDATE branch (row already exists) clears deleted_at and
                // bumps the revision; created_at is preserved. On a fresh add this is a plain insert.
                revision = reusedFolder?.revision ?: 0L,
                updatedAt = now,
                createdAt = reusedFolder?.createdAt ?: now,
                deletedAt = null,
            )
        return when (val result = libraryFolderRepository.upsert(folderPayload)) {
            is AppResult.Failure -> {
                AppResult.Failure(result.error)
            }

            is AppResult.Success -> {
                // Revive the reused folder's tombstoned books under their original ids BEFORE the
                // rescan, so clients' saved references resolve immediately; the rescan then refreshes
                // content in place (the revival guardrail forces a write over a tombstone).
                if (reusedFolder != null) {
                    for (bookId in bookRepository.idsByFolder(folderId)) {
                        bookRepository.reviveById(bookId)
                    }
                }
                val folder = LibraryFolder(id = folderId, libraryId = libraryId, rootPath = path, createdAt = now)
                val folderRef = LibraryFolderRef(id = folderId, rootPath = path)
                scanOrchestrator.onFolderAdded(libraryId, folderRef)
                AppResult.Success(folder)
            }
        }
    }

    override suspend fun removeFolder(folderId: FolderId): AppResult<Unit> {
        requireAdmin()?.let { return AppResult.Failure(it) }
        val folderPage = libraryFolderRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val existing =
            folderPage.items.firstOrNull { it.id == folderId.value }
                ?: return AppResult.Failure(LibraryError.FolderNotFound())
        if (existing.deletedAt != null) return AppResult.Success(Unit) // idempotent

        // Soft-delete books in this folder
        val bookPage = bookRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val folderBooks = bookPage.items.filter { it.folderId == folderId && it.deletedAt == null }
        for (book in folderBooks) {
            bookRepository.softDelete(
                com.calypsan.listenup.core
                    .BookId(book.id),
            )
        }
        // Soft-delete folder
        libraryFolderRepository.softDelete(folderId)

        // Tear down watcher AFTER DB mutations
        scanOrchestrator.onFolderRemoved(folderId)
        return AppResult.Success(Unit)
    }

    // ── Scan triggers ────────────────────────────────────────────────────────

    override suspend fun scanLibrary(): AppResult<Unit> {
        // Admin-only: triggering a scan is a privileged server operation.
        requireAdmin()?.let { return AppResult.Failure(it) }
        return scanOrchestrator.scanLibraryAsync(libraryRegistry.currentLibrary())
    }

    override suspend fun scanFolder(folderId: FolderId): AppResult<Unit> {
        // Admin-only: triggering a scan is a privileged server operation.
        requireAdmin()?.let { return AppResult.Failure(it) }
        val folderPage = libraryFolderRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val folderExists = folderPage.items.any { it.id == folderId.value && it.deletedAt == null }
        if (!folderExists) return AppResult.Failure(LibraryError.FolderNotFound())
        scanOrchestrator.scanFolder(folderId)
        return AppResult.Success(Unit)
    }

    // ── Principal binding ────────────────────────────────────────────────────

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): LibraryAdminServiceImpl =
        LibraryAdminServiceImpl(
            libraryRepository = libraryRepository,
            libraryFolderRepository = libraryFolderRepository,
            bookRepository = bookRepository,
            scanOrchestrator = scanOrchestrator,
            libraryRegistry = libraryRegistry,
            clock = clock,
            principal = principal,
        )

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Admin gate: null when the caller is ROOT/ADMIN; [AuthError.PermissionDenied] for a
     * member, and for an absent principal (a wiring bug — route handlers always [copyWith]
     * the authenticated caller — denied rather than run unauthenticated).
     */
    private fun requireAdmin(): AppError? {
        val role = principal.current()?.role ?: return AuthError.PermissionDenied()
        return if (role == UserRole.ROOT || role == UserRole.ADMIN) null else AuthError.PermissionDenied()
    }

    /**
     * Checks whether any of [paths] is already registered as an active folder
     * under any library. Returns [AppResult.Failure] with [LibraryError.DuplicateFolder]
     * on the first conflict, or `null` if all paths are available.
     */
    private suspend fun checkForDuplicateFolders(paths: List<String>): AppResult<Nothing>? {
        val folderPage = libraryFolderRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val activePaths =
            folderPage.items
                .filter { it.deletedAt == null }
                .map { it.rootPath }
                .toSet()
        return paths.firstOrNull { it in activePaths }?.let { duplicate ->
            AppResult.Failure(LibraryError.DuplicateFolder(debugInfo = "Path already registered: $duplicate"))
        }
    }

    /**
     * Builds a full [Library] DTO from [LibrarySyncPayload] by fetching its active
     * folder refs from [libraryFolderRepository].
     *
     * Folder [rootPath]s are absolute server filesystem paths — operator disk topology.
     * They are exposed to ROOT/ADMIN callers and to internal/system reads (no principal —
     * e.g. bootstrap, which feeds the paths to the scanner); a plain member receives folder
     * refs with `rootPath = null` (count + identity preserved, path redacted). Mirrors the
     * firehose `library_folders` gate so members never learn folder paths through any channel.
     */
    private suspend fun LibrarySyncPayload.toLibraryWithFolders(): Library {
        // System/bootstrap reads (no principal) and admins see real paths; only an authenticated
        // member has them redacted. Route handlers always bind a principal via copyWith, so a null
        // principal here is always an internal/system caller — never a wire request.
        val role = principal.current()?.role
        val exposePaths = role == null || role == UserRole.ROOT || role == UserRole.ADMIN
        val folderPage = libraryFolderRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val folderRefs =
            folderPage.items
                .filter { it.libraryId == this.id && it.deletedAt == null }
                .map { LibraryFolderRef(id = FolderId(it.id), rootPath = if (exposePaths) it.rootPath else null) }
        return Library(
            id = LibraryId(this.id),
            name = this.name,
            folders = folderRefs,
            metadataPrecedence = this.metadataPrecedence,
            accessMode =
                runCatching { AccessMode.valueOf(this.accessMode.uppercase()) }
                    .getOrDefault(AccessMode.SHARED),
            createdByUserId =
                this.createdByUserId?.let {
                    com.calypsan.listenup.api.dto.auth
                        .UserId(it)
                },
            createdAt = this.createdAt,
        )
    }
}
