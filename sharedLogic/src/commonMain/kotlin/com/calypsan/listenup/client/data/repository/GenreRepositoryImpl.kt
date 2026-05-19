package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.mapSuspend
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.remote.GenreApiContract
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementation of [GenreRepository] using Room and GenreApi.
 *
 * Read methods are local-only and return plain values. Server-touching
 * write methods return [AppResult]; on success, the local cache is updated
 * for immediate reactivity, on failure the cache is left untouched and the
 * typed error is propagated unchanged.
 *
 * @property dao Room DAO for genre operations
 * @property genreApi API client for server genre operations
 */
class GenreRepositoryImpl(
    private val dao: GenreDao,
    private val genreApi: GenreApiContract,
) : GenreRepository {
    override fun observeAll(): Flow<List<Genre>> =
        dao.observeAllGenres().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getAll(): List<Genre> = dao.getAllGenres().map { it.toDomain() }

    override suspend fun getById(id: String): Genre? = dao.getById(id)?.toDomain()

    override suspend fun getBySlug(slug: String): Genre? = dao.getBySlug(slug)?.toDomain()

    override fun observeGenresForBook(bookId: String): Flow<List<Genre>> =
        dao.observeGenresForBook(BookId(bookId)).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getGenresForBook(bookId: String): List<Genre> =
        dao.getGenresForBook(BookId(bookId)).map { it.toDomain() }

    override suspend fun getBookIdsForGenre(genreId: String): List<String> =
        dao.getBookIdsForGenre(genreId).map { it.value }

    override suspend fun setGenresForBook(
        bookId: String,
        genreIds: List<String>,
    ): AppResult<Unit> =
        genreApi
            .setBookGenres(bookId, genreIds)
            .mapSuspend { dao.replaceGenresForBook(BookId(bookId), genreIds) }

    override suspend fun createGenre(
        name: String,
        parentId: String?,
    ): AppResult<Genre> = genreApi.createGenre(name, parentId)

    override suspend fun updateGenre(
        id: String,
        name: String,
    ): AppResult<Genre> = genreApi.updateGenre(id, name)

    override suspend fun deleteGenre(id: String): AppResult<Unit> =
        genreApi
            .deleteGenre(id)
            .mapSuspend { dao.deleteById(id) }

    override suspend fun moveGenre(
        id: String,
        newParentId: String?,
    ): AppResult<Unit> = genreApi.moveGenre(id, newParentId)
}

/**
 * Convert GenreEntity to Genre domain model.
 */
private fun GenreEntity.toDomain(): Genre =
    Genre(
        id = id,
        name = name,
        slug = slug,
        path = path,
        bookCount = bookCount,
    )
