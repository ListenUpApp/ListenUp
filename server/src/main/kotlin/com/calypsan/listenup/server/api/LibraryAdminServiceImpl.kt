package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.CreateLibraryRequest
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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.util.UUID
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Server-side implementation of [LibraryAdminService].
 *
 * Manages the full lifecycle of libraries and their root folders through the
 * syncable-substrate repositories. Lifecycle mutations (create, delete, add/remove
 * folder) run inside a single [suspendTransaction] for atomicity, then call out
 * to [scanOrchestrator] **after** the transaction commits so the orchestrator's
 * watcher/scanner state reflects the committed DB state.
 *
 * Library structure is ADMIN territory. Every mutating op — create/delete library,
 * add/remove folder, scan triggers — and the filesystem-exposing [browseFilesystem]
 * are gated through [requireAdmin] (ROOT/ADMIN pass; everyone else gets
 * [AuthError.PermissionDenied]). The read-only [listLibraries]/[getLibrary]/[getSetupStatus]
 * stay open to any authenticated caller: members need to list and resolve libraries for
 * normal content browsing, and the setup status drives first-launch onboarding.
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

    override suspend fun listLibraries(): AppResult<List<Library>> {
        // Open to any authenticated caller: members list libraries to browse content.
        val page = libraryRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val active = page.items.filter { it.deletedAt == null }
        val libraries = active.map { it.toLibraryWithFolders() }
        return AppResult.Success(libraries)
    }

    override suspend fun getLibrary(id: LibraryId): AppResult<Library?> {
        // Open to any authenticated caller: members resolve a library by id while browsing.
        val page = libraryRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val payload =
            page.items.firstOrNull { it.id == id.value && it.deletedAt == null }
                ?: return AppResult.Success(null)
        return AppResult.Success(payload.toLibraryWithFolders())
    }

    override suspend fun fetchLibrary(): AppResult<Library> {
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
        val libPage = libraryRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val libraryCount = libPage.items.count { it.deletedAt == null }
        return AppResult.Success(
            SetupStatus(needsSetup = folderCount == 0, libraryCount = libraryCount, isScanning = scanOrchestrator.isScanning()),
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

    // ── Library lifecycle ────────────────────────────────────────────────────

    override suspend fun createLibrary(request: CreateLibraryRequest): AppResult<Library> {
        requireAdmin()?.let { return AppResult.Failure(it) }
        // Single-library invariant: one library per server. If one already exists, return it
        // (get-or-create) rather than creating a second — keeps the setup flow idempotent.
        listLibraries().let { existing ->
            if (existing is AppResult.Success && existing.data.isNotEmpty()) {
                return AppResult.Success(existing.data.first())
            }
        }
        return createNewLibrary(request)
    }

    private suspend fun createNewLibrary(request: CreateLibraryRequest): AppResult<Library> {
        if (request.folderPaths.isEmpty()) {
            return AppResult.Failure(LibraryError.InvalidPath(debugInfo = "At least one folder path is required."))
        }
        // Validate all paths exist before touching the DB
        for (folderPath in request.folderPaths) {
            val dir = Path(folderPath)
            if (!SystemFileSystem.exists(dir) || SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) {
                return AppResult.Failure(
                    LibraryError.InvalidPath(debugInfo = "Path does not exist or is not a directory: $folderPath"),
                )
            }
        }
        // Check for duplicate folder paths (any active folder across all libraries)
        val duplicateCheck = checkForDuplicateFolders(request.folderPaths)
        if (duplicateCheck != null) return duplicateCheck

        val now = clock.now().toEpochMilliseconds()
        val libraryId = LibraryId(UUID.randomUUID().toString())
        val precedence = request.metadataPrecedence ?: "embedded,abs,sidecar"

        // Create library row
        val libraryPayload =
            LibrarySyncPayload(
                id = libraryId.value,
                name = request.name,
                metadataPrecedence = precedence,
                accessMode = AccessMode.SHARED.name.lowercase(),
                createdByUserId = null,
                revision = 0L,
                updatedAt = now,
                createdAt = now,
                deletedAt = null,
            )
        when (val result = libraryRepository.upsert(libraryPayload)) {
            is AppResult.Failure -> return AppResult.Failure(result.error)
            is AppResult.Success -> Unit
        }

        // Create folder rows
        val folderRefs =
            request.folderPaths.map { folderPath ->
                val folderId = FolderId(UUID.randomUUID().toString())
                val folderPayload =
                    LibraryFolderSyncPayload(
                        id = folderId.value,
                        libraryId = libraryId.value,
                        rootPath = folderPath,
                        revision = 0L,
                        updatedAt = now,
                        createdAt = now,
                        deletedAt = null,
                    )
                when (val result = libraryFolderRepository.upsert(folderPayload)) {
                    is AppResult.Failure -> return AppResult.Failure(result.error)
                    is AppResult.Success -> Unit
                }
                LibraryFolderRef(id = folderId, rootPath = folderPath)
            }

        val library =
            Library(
                id = libraryId,
                name = request.name,
                folders = folderRefs,
                metadataPrecedence = precedence,
                accessMode = AccessMode.SHARED,
                createdByUserId = null,
                createdAt = now,
                inboxEnabled = false,
            )

        // Register with orchestrator AFTER DB commit
        scanOrchestrator.onLibraryAdded(library)
        return AppResult.Success(library)
    }

    override suspend fun renameLibrary(
        id: LibraryId,
        name: String,
    ): AppResult<Library> {
        requireAdmin()?.let { return AppResult.Failure(it) }
        val page = libraryRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val existing =
            page.items.firstOrNull { it.id == id.value && it.deletedAt == null }
                ?: return AppResult.Failure(LibraryError.NotFound())

        val now = clock.now().toEpochMilliseconds()
        val updated = existing.copy(name = name, updatedAt = now)
        return when (val result = libraryRepository.upsert(updated)) {
            is AppResult.Failure -> AppResult.Failure(result.error)
            is AppResult.Success -> AppResult.Success(result.data.toLibraryWithFolders())
        }
    }

    override suspend fun deleteLibrary(id: LibraryId): AppResult<Unit> {
        requireAdmin()?.let { return AppResult.Failure(it) }
        val page = libraryRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val existing =
            page.items.firstOrNull { it.id == id.value }
                ?: return AppResult.Failure(LibraryError.NotFound())
        if (existing.deletedAt != null) return AppResult.Success(Unit) // idempotent

        // Cascade: soft-delete books → folders → library (one transaction per item
        // since softDelete opens its own transaction; orchestration is sequential)
        val folderPage = libraryFolderRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val activeFolders = folderPage.items.filter { it.libraryId == id.value && it.deletedAt == null }

        // Soft-delete books belonging to this library's folders
        val bookPage = bookRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        val activeBooks = bookPage.items.filter { it.libraryId == id && it.deletedAt == null }
        for (book in activeBooks) {
            bookRepository.softDelete(
                com.calypsan.listenup.core
                    .BookId(book.id),
            )
        }
        // Soft-delete folders
        for (folder in activeFolders) {
            libraryFolderRepository.softDelete(FolderId(folder.id))
        }
        // Soft-delete library
        libraryRepository.softDelete(id)

        // Tear down scanner AFTER DB mutations
        scanOrchestrator.onLibraryRemoved(id)
        return AppResult.Success(Unit)
    }

    // ── Folder lifecycle ─────────────────────────────────────────────────────

    override suspend fun addFolder(
        libraryId: LibraryId,
        path: String,
    ): AppResult<LibraryFolder> {
        requireAdmin()?.let { return AppResult.Failure(it) }
        val libraryPage = libraryRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        libraryPage.items.firstOrNull { it.id == libraryId.value && it.deletedAt == null }
            ?: return AppResult.Failure(LibraryError.NotFound())
        return addFolderTo(libraryId, path)
    }

    override suspend fun addFolderToLibrary(path: String): AppResult<LibraryFolder> {
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
        val folderId = FolderId(UUID.randomUUID().toString())
        val folderPayload =
            LibraryFolderSyncPayload(
                id = folderId.value,
                libraryId = libraryId.value,
                rootPath = path,
                revision = 0L,
                updatedAt = now,
                createdAt = now,
                deletedAt = null,
            )
        return when (val result = libraryFolderRepository.upsert(folderPayload)) {
            is AppResult.Failure -> AppResult.Failure(result.error)
            is AppResult.Success -> {
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

    override suspend fun scanLibrary(libraryId: LibraryId): AppResult<Unit> {
        // Admin-only: triggering a scan is a privileged server operation.
        requireAdmin()?.let { return AppResult.Failure(it) }
        // Fire-and-forget: kick the scan off and return "accepted" immediately. The scan
        // runs on the server's lifecycle scope and streams progress over SSE, so the
        // caller (admin RPC / onboarding wizard) never blocks on the full walk.
        return scanOrchestrator.scanLibraryAsync(libraryId)
    }

    override suspend fun triggerLibraryScan(): AppResult<Unit> {
        // Admin-only: triggering a scan is a privileged server operation.
        requireAdmin()?.let { return AppResult.Failure(it) }
        return scanOrchestrator.scanLibraryAsync(libraryRegistry.currentLibrary())
    }

    override suspend fun scanFolder(folderId: FolderId): AppResult<Unit> {
        // Admin-only: triggering a scan is a privileged server operation.
        requireAdmin()?.let { return AppResult.Failure(it) }
        val folderPage = libraryFolderRepository.pullSince(userId = null, cursor = 0L, limit = Int.MAX_VALUE)
        folderPage.items.firstOrNull { it.id == folderId.value && it.deletedAt == null }
            ?: return AppResult.Failure(LibraryError.FolderNotFound())
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
            inboxEnabled = libraryRepository.readInboxEnabled(LibraryId(this.id)),
        )
    }
}
