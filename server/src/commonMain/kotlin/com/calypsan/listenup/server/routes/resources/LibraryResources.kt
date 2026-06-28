package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST mirror of the [com.calypsan.listenup.api.LibraryAdminService] RPC surface.
 * All routes live under `/api/v1/libraries`.
 *
 * Every route requires JWT authentication. Admin operations (add/remove folder,
 * scan) use `POST` or `DELETE` verbs; read operations use `GET`.
 *
 * The [Collection] class serves as the parent resource for all nested routes and
 * as the route for `GET /api/v1/libraries` (fetch THE library in the single-library model).
 */
@Resource("/api/v1/libraries")
class LibraryResources {
    /**
     * `GET /api/v1/libraries` — REST mirror of
     * [com.calypsan.listenup.api.LibraryAdminService.getLibrary].
     *
     * Also serves as the parent anchor for all nested library resource routes.
     */
    @Resource("")
    class Collection(
        val parent: LibraryResources = LibraryResources(),
    )

    /**
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.getSetupStatus] —
     * `GET /api/v1/libraries/setup-status` returns whether onboarding is needed.
     */
    @Resource("setup-status")
    class SetupStatus(
        val parent: LibraryResources = LibraryResources(),
    )

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
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.addFolder] —
     * `POST /api/v1/libraries/folders` registers a new root folder under THE library.
     */
    @Resource("folders")
    class Folders(
        val parent: LibraryResources = LibraryResources(),
    )

    /**
     * REST mirror for per-folder operations:
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

    /**
     * REST mirror for [com.calypsan.listenup.api.LibraryAdminService.scanLibrary] —
     * `POST /api/v1/libraries/scan` triggers a full scan of THE library.
     */
    @Resource("scan")
    class Scan(
        val parent: LibraryResources = LibraryResources(),
    )
}
