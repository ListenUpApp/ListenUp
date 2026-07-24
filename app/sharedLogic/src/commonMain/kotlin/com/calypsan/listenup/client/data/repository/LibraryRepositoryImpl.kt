package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.dao.LibraryDao
import com.calypsan.listenup.client.data.local.db.dao.LibraryFolderDao
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.LibraryFolder
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [LibraryRepository].
 *
 * All methods read exclusively from Room — the local database is the single
 * source of truth. Libraries and folders are applied by their respective sync
 * domain handlers and never fetched on demand here.
 */
internal class LibraryRepositoryImpl(
    private val libraryDao: LibraryDao,
    private val libraryFolderDao: LibraryFolderDao,
) : LibraryRepository {
    override fun observeAll(): Flow<List<Library>> =
        libraryDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Library?> = libraryDao.observeById(id).map { it?.toDomain() }

    override fun observeFolders(libraryId: String): Flow<List<LibraryFolder>> =
        libraryFolderDao.observeForLibrary(libraryId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun findById(id: String): Library? = libraryDao.findById(id)?.toDomain()

    override suspend fun findFolderById(folderId: String): LibraryFolder? =
        libraryFolderDao.findById(folderId)?.toDomain()
}

private fun LibraryEntity.toDomain(): Library =
    Library(
        id = id,
        name = name,
        metadataPrecedence = metadataPrecedence,
        accessMode = AccessMode.fromString(accessMode),
        createdByUserId = createdByUserId,
        createdAt = createdAt,
        revision = revision,
    )

private fun LibraryFolderEntity.toDomain(): LibraryFolder =
    LibraryFolder(
        id = id,
        libraryId = libraryId,
        rootPath = rootPath,
        createdAt = createdAt,
        revision = revision,
    )
