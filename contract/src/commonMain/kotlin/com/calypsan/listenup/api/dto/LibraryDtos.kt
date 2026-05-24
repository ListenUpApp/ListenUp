package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire representation of a Library — the top-level grouping entity that owns N
 * [LibraryFolder]s and scopes a collection of audiobooks.
 *
 * Libraries are server-wide (cross-user) for now. [accessMode] and
 * [createdByUserId] are forward-staged for the Multi-user phase: they carry
 * default values (`SHARED` / `null`) and are not enforced until per-user
 * permission gating lands.
 *
 * The [folders] list contains lightweight [LibraryFolderRef] snapshots — enough
 * for display and folder-lifecycle operations without a separate fetch.
 */
@Serializable
@SerialName("Library")
data class Library(
    /** Stable library identifier. UUIDv7 at the storage layer. */
    val id: LibraryId,
    /** Human-readable name, e.g. "My Audiobooks" or "Kids Library". */
    val name: String,
    /** Lightweight refs to every root folder registered under this library. */
    val folders: List<LibraryFolderRef>,
    /**
     * Comma-separated metadata source precedence, e.g. `"embedded,abs,sidecar"`.
     * The scanner uses this to rank competing metadata sources when enriching books.
     */
    val metadataPrecedence: String,
    /**
     * Who can see this library. Defaults to [AccessMode.SHARED] (all users).
     * Forward-staged for Multi-user phase — not enforced until permissions land.
     */
    val accessMode: AccessMode,
    /**
     * The user who created this library. `null` for bootstrap-created libraries
     * (env-var path at first boot) or pre-Multi-user libraries.
     * Forward-staged for Multi-user phase.
     */
    val createdByUserId: UserId?,
    /** Unix epoch milliseconds when this library was created. */
    val createdAt: Long,
)

/**
 * Wire representation of a single root folder within a [Library].
 *
 * Each folder maps to one filesystem root path that the scanner walks. A
 * library may have N folders; books discovered under each folder belong to the
 * parent library.
 */
@Serializable
@SerialName("LibraryFolder")
data class LibraryFolder(
    /** Stable folder identifier. UUIDv7 at the storage layer. */
    val id: FolderId,
    /** The library this folder belongs to. */
    val libraryId: LibraryId,
    /** Absolute filesystem path the scanner walks for this folder. */
    val rootPath: String,
    /** Unix epoch milliseconds when this folder was registered. */
    val createdAt: Long,
)

/**
 * Lightweight reference to a [LibraryFolder] — id + path only.
 *
 * Embedded in [Library.folders] so clients can display folder paths and
 * trigger per-folder operations without a separate [LibraryFolder] fetch.
 */
@Serializable
@SerialName("LibraryFolderRef")
data class LibraryFolderRef(
    /** Stable folder identifier. */
    val id: FolderId,
    /** Absolute filesystem path the scanner walks for this folder. */
    val rootPath: String,
)

/**
 * Request body for [com.calypsan.listenup.api.LibraryAdminService.createLibrary].
 *
 * [folderPaths] must contain at least one path. The server validates that each
 * path exists and is readable; invalid paths produce [LibraryError.InvalidPath].
 * Duplicate paths (already registered under another library) produce
 * [LibraryError.DuplicateFolder].
 */
@Serializable
@SerialName("CreateLibraryRequest")
data class CreateLibraryRequest(
    /** Human-readable name for the new library. */
    val name: String,
    /**
     * One or more absolute filesystem paths to register as folders.
     * Must be non-empty; each path must exist and be readable on the server.
     */
    val folderPaths: List<String>,
    /**
     * Optional metadata source precedence override. `null` means the server
     * applies its default precedence (`"embedded,abs,sidecar"`).
     */
    val metadataPrecedence: String? = null,
    /**
     * When `true` the server skips the inbox folder convention during scanning.
     * Preserved from the legacy `SetupApiContract` surface; verify semantic
     * validity during Library UI phase.
     */
    val skipInbox: Boolean = false,
)

/**
 * Wire response for [com.calypsan.listenup.api.LibraryAdminService.getSetupStatus].
 *
 * [needsSetup] is `true` when no libraries exist on the server — the client
 * onboarding wizard should show the library creation flow. Once at least one
 * library exists, [needsSetup] is `false` and [libraryCount] reflects the
 * current total.
 */
@Serializable
@SerialName("SetupStatus")
data class SetupStatus(
    /** `true` when the server has no libraries and needs onboarding. */
    val needsSetup: Boolean,
    /** Total number of non-deleted libraries on the server. */
    val libraryCount: Int,
)

/**
 * One entry returned by [com.calypsan.listenup.api.LibraryAdminService.browseFilesystem].
 *
 * Represents a single sub-directory at the browsed path. Clients use [hasChildren]
 * to decide whether to show an expand affordance without issuing a recursive fetch.
 */
@Serializable
@SerialName("DirectoryEntry")
data class DirectoryEntry(
    /** Display name of the directory (leaf component of [path]). */
    val name: String,
    /** Absolute server-side path of this directory. */
    val path: String,
    /** Whether this directory has at least one sub-directory. */
    val hasChildren: Boolean,
)

/**
 * Library access mode — controls which users can see and play from a library.
 *
 * Forward-staged for the Multi-user phase: the column exists and is stored, but
 * enforcement is deferred until per-user permission gating lands. All libraries
 * behave as [SHARED] regardless of the stored value until that phase.
 *
 * - [SHARED] — visible to all authenticated users (default).
 * - [PRIVATE] — visible only to the library owner ([Library.createdByUserId]).
 * - [RESTRICTED] — visible to an explicit allowlist (managed in Multi-user phase).
 */
@Serializable
enum class AccessMode {
    SHARED,
    PRIVATE,
    RESTRICTED,
}
