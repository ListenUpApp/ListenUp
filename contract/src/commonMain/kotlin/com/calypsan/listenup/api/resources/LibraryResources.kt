package com.calypsan.listenup.api.resources

import io.ktor.resources.Resource

/**
 * REST mirror of the [com.calypsan.listenup.api.LibraryAdminService] RPC surface.
 * All routes live under `/api/v1/libraries`.
 *
 * Every route requires JWT authentication. Admin operations (create, rename,
 * delete, add/remove folder, scan) use `POST`, `PATCH`, or `DELETE` verbs;
 * read operations use `GET`.
 *
 * The [Collection] class doubles as the parent resource and the route for
 * `GET /api/v1/libraries` (list all libraries).
 */
@Resource("/api/v1/libraries")
class LibraryResources {

    /**
     * REST mirror of [com.calypsan.listenup.api.LibraryAdminService.listLibraries] —
     * `GET /api/v1/libraries` returns all non-deleted libraries with folders.
     *
     * Also `POST /api/v1/libraries` mirrors
     * [com.calypsan.listenup.api.LibraryAdminService.createLibrary] with a
     * [com.calypsan.listenup.api.dto.CreateLibraryRequest] body.
     */
    @Resource("")
    class Collection(val parent: LibraryResources = LibraryResources())

    /**
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.getSetupStatus] —
     * `GET /api/v1/libraries/setup-status` returns whether onboarding is needed.
     */
    @Resource("setup-status")
    class SetupStatus(val parent: LibraryResources = LibraryResources())

    /**
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.browseFilesystem] —
     * `GET /api/v1/libraries/browse?path=…` lists immediate sub-directories of [path]
     * on the server filesystem.
     */
    @Resource("browse")
    class Browse(
        val parent: LibraryResources = LibraryResources(),
        /** Absolute server-side path to list sub-directories for. */
        val path: String,
    )

    /**
     * REST mirror for per-library operations:
     * - `GET /api/v1/libraries/{id}` → [com.calypsan.listenup.api.LibraryAdminService.getLibrary]
     * - `PATCH /api/v1/libraries/{id}` → [com.calypsan.listenup.api.LibraryAdminService.renameLibrary]
     * - `DELETE /api/v1/libraries/{id}` → [com.calypsan.listenup.api.LibraryAdminService.deleteLibrary]
     *   (cascade-deletes books + folders)
     */
    @Resource("{id}")
    class Detail(
        val parent: LibraryResources = LibraryResources(),
        /** Library id string. */
        val id: String,
    )

    /**
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.scanLibrary] —
     * `POST /api/v1/libraries/{id}/scan` triggers a full scan of library [id].
     */
    @Resource("{id}/scan")
    class Scan(
        val parent: LibraryResources = LibraryResources(),
        /** Library id string. */
        val id: String,
    )

    /**
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.addFolder] —
     * `POST /api/v1/libraries/{id}/folders` registers a new folder under library [id].
     */
    @Resource("{id}/folders")
    class Folders(
        val parent: LibraryResources = LibraryResources(),
        /** Library id string. */
        val id: String,
    )

    /**
     * REST mirror for per-folder operations:
     * - `GET /api/v1/libraries/folders/{folderId}` — fetch a single folder.
     * - `DELETE /api/v1/libraries/folders/{folderId}` → [com.calypsan.listenup.api.LibraryAdminService.removeFolder]
     *   (cascade-deletes folder's books)
     */
    @Resource("folders/{folderId}")
    class FolderDetail(
        val parent: LibraryResources = LibraryResources(),
        /** Folder id string. */
        val folderId: String,
    )

    /**
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.scanFolder] —
     * `POST /api/v1/libraries/folders/{folderId}/scan` triggers an incremental scan
     * of folder [folderId].
     */
    @Resource("folders/{folderId}/scan")
    class FolderScan(
        val parent: LibraryResources = LibraryResources(),
        /** Folder id string. */
        val folderId: String,
    )
}
