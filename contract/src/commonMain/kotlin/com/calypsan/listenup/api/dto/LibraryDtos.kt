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
 * Libraries are server-wide (cross-user). [accessMode] and
 * [createdByUserId] are forward-staged for multi-user support: they carry
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
    /** Human-readable name. Defaults to "Library" for the bootstrapped singleton. */
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
     * Forward-staged for multi-user support — not enforced until permissions land.
     */
    val accessMode: AccessMode,
    /**
     * The user who created this library. `null` for bootstrap-created libraries
     * (env-var path at first boot) or pre-multi-user libraries.
     * Forward-staged for multi-user support.
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
 * Lightweight reference to a [LibraryFolder] — id, plus the folder's root path
 * for callers permitted to see it.
 *
 * Embedded in [Library.folders] so clients can count folders and (for admins)
 * display folder paths without a separate [LibraryFolder] fetch.
 *
 * [rootPath] is the absolute server filesystem path the scanner walks — operator
 * disk topology. It is populated only for ROOT/ADMIN callers; for a plain member
 * it is `null` (redacted), so the member projection carries folder identity and
 * count without leaking the server's directory layout. The firehose `library_folders`
 * sync domain is likewise admin-only, so members never receive folder paths through
 * any channel.
 */
@Serializable
@SerialName("LibraryFolderRef")
data class LibraryFolderRef(
    /** Stable folder identifier. */
    val id: FolderId,
    /** Absolute filesystem path the scanner walks for this folder; `null` when redacted for a non-admin caller. */
    val rootPath: String?,
)

/**
 * Wire response for [com.calypsan.listenup.api.LibraryAdminService.getSetupStatus].
 *
 * [needsSetup] is `true` when THE library has no folders yet — the client onboarding
 * wizard should prompt the operator to add at least one root folder.
 */
@Serializable
@SerialName("SetupStatus")
data class SetupStatus(
    /** `true` when THE library has no folders yet and needs onboarding. */
    val needsSetup: Boolean,
    /** `true` while a library scan is in progress, for onboarding-status spinners. */
    val isScanning: Boolean = false,
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
    /** Number of immediate entries (files + sub-directories) inside this directory. */
    val itemCount: Int,
)

/**
 * Library access mode — controls which users can see and play from a library.
 *
 * Forward-staged for multi-user support: the column exists and is stored, but
 * enforcement is deferred until per-user permission gating lands. All libraries
 * behave as [SHARED] regardless of the stored value until then.
 *
 * - [SHARED] — visible to all authenticated users (default).
 * - [PRIVATE] — visible only to the library owner ([Library.createdByUserId]).
 * - [RESTRICTED] — visible to an explicit allowlist (managed under multi-user support).
 */
@Serializable
enum class AccessMode {
    SHARED,
    PRIVATE,
    RESTRICTED,
}
