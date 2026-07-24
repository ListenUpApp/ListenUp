package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.LibraryFolder
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for observing libraries and their folders from the local Room database.
 *
 * All methods observe Room exclusively — no network calls. Libraries and folders are kept in
 * sync by [com.calypsan.listenup.client.data.sync.domains.librariesDomain] and
 * [com.calypsan.listenup.client.data.sync.domains.libraryFoldersDomain] respectively.
 *
 * Mutations (create, rename, delete, add/remove folder) go through the `LibraryAdminService`
 * RPC factory on the server and arrive here via sync catch-up.
 *
 * Part of the domain layer — implementations live in the data layer.
 */
interface LibraryRepository {
    /**
     * Observe all live libraries reactively, ordered by name.
     *
     * Tombstoned rows are excluded. Emits an empty list when no libraries are present.
     */
    fun observeAll(): Flow<List<Library>>

    /**
     * Observe a single library by ID reactively. Emits null when absent or tombstoned.
     *
     * @param id The library ID.
     */
    fun observeById(id: String): Flow<Library?>

    /**
     * Observe all live folders for a specific library reactively.
     *
     * Tombstoned folder rows are excluded. Emits an empty list when the library has no folders.
     *
     * @param libraryId The parent library ID.
     */
    fun observeFolders(libraryId: String): Flow<List<LibraryFolder>>

    /**
     * Snapshot lookup of a library by ID. Returns null when absent or tombstoned.
     *
     * @param id The library ID.
     */
    suspend fun findById(id: String): Library?

    /**
     * Snapshot lookup of a folder by ID. Returns null when absent.
     *
     * @param folderId The folder ID.
     */
    suspend fun findFolderById(folderId: String): LibraryFolder?
}
