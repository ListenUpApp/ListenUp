package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookTagCrossRef
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.TagApiContract
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * Implementation of TagRepository using Room and TagApi.
 *
 * Handles tag operations with API calls and local Room updates
 * for immediate reactivity.
 *
 * @property dao Room DAO for tag operations
 * @property tagApi API client for server tag operations
 */
class TagRepositoryImpl(
    private val dao: TagDao,
    private val tagApi: TagApiContract,
) : TagRepository {
    override fun observeAll(): Flow<List<Tag>> =
        dao.observeAllTags().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getAll(): List<Tag> = dao.getAllTags().map { it.toDomain() }

    override suspend fun getById(id: String): Tag? = dao.getById(id)?.toDomain()

    override fun observeById(id: String): Flow<Tag?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getBySlug(slug: String): Tag? = dao.getBySlug(slug)?.toDomain()

    override fun observeTagsForBook(bookId: String): Flow<List<Tag>> =
        dao.observeTagsForBook(BookId(bookId)).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getTagsForBook(bookId: String): List<Tag> =
        dao.getTagsForBook(BookId(bookId)).map { it.toDomain() }

    override suspend fun getBookIdsForTag(tagId: String): List<String> = dao.getBookIdsForTag(tagId).map { it.value }

    override fun observeBookIdsForTag(tagId: String): Flow<List<String>> =
        dao.observeBookIdsForTag(tagId).map { bookIds ->
            bookIds.map { it.value }
        }

    override suspend fun addTagToBook(
        bookId: String,
        tagSlugOrName: String,
    ): AppResult<Tag> {
        // Call API to add tag (creates if doesn't exist)
        val result = tagApi.addTagToBook(bookId, tagSlugOrName)
        if (result is AppResult.Success) {
            val tag = result.data
            // Update local Room for immediate reactivity
            val entity =
                TagEntity(
                    id = tag.id,
                    slug = tag.slug,
                    bookCount = tag.bookCount,
                    createdAt = tag.createdAt?.let { Timestamp(it.toEpochMilliseconds()) } ?: Timestamp.now(),
                )
            dao.upsert(entity)
            dao.insertBookTag(BookTagCrossRef(bookId = BookId(bookId), tagId = tag.id))
        }
        return result
    }

    override suspend fun removeTagFromBook(
        bookId: String,
        tagSlug: String,
        tagId: String,
    ): AppResult<Unit> {
        // Call API to remove tag
        val result = tagApi.removeTagFromBook(bookId, tagSlug)
        if (result is AppResult.Success) {
            // Update local Room for immediate reactivity
            dao.deleteBookTag(BookId(bookId), tagId)
        }
        return result
    }
}

/**
 * Convert TagEntity to Tag domain model.
 */
private fun TagEntity.toDomain(): Tag =
    Tag(
        id = id,
        slug = slug,
        bookCount = bookCount,
        createdAt = Instant.fromEpochMilliseconds(createdAt.epochMillis),
    )
