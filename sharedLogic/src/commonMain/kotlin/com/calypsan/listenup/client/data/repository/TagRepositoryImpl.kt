package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.BookTagDao
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.TagRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Production implementation of [TagRepository].
 *
 * **Observation** (Room-backed, offline-first): all `observe*` and `getTagBySlug` calls
 * read from Room. The SSE sync engine writes server-committed state into Room via
 * [com.calypsan.listenup.client.data.sync.domains.tagsDomain] and
 * [com.calypsan.listenup.client.data.sync.domains.bookTagsDomain], so the
 * UI reacts without explicit network polling.
 *
 * **Mutation** (RPC-backed): `addTagToBook`, `removeTagFromBook`, `renameTag`, `deleteTag`
 * dispatch through the bounded, self-healing [RpcChannel] for [TagService]. There are no
 * optimistic Room writes — the SSE echo from the server is the single write path back into
 * Room, keeping state consistent across devices.
 *
 * @property channel the [RpcChannel] the mutation surface dispatches through; the channel folds
 *   the RPC outcome into an [AppResult] (throw → typed `Failure`, business `Failure` passthrough).
 */
internal class TagRepositoryImpl(
    private val channel: RpcChannel<TagService>,
    private val tagDao: TagDao,
    private val bookTagDao: BookTagDao,
) : TagRepository {
    // ── Observation (Room-backed) ─────────────────────────────────────────────

    override fun observeAllTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeTagsForBook(bookId: String): Flow<List<Tag>> =
        tagDao.observeForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getTagBySlug(slug: String): AppResult<Tag?> =
        try {
            val entity = tagDao.findBySlug(slug)
            AppResult.Success(entity?.toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "getTagBySlug($slug) failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }

    override fun observeById(id: String): Flow<Tag?> = tagDao.observeById(id).map { it?.toDomain() }

    override fun observeBookIdsForTag(tagId: String): Flow<List<String>> =
        bookTagDao.observeForTag(tagId).map { rows -> rows.map { it.bookId } }

    // ── Mutation (RPC-backed) ─────────────────────────────────────────────────

    override suspend fun addTagToBook(
        bookId: String,
        name: String,
    ): AppResult<Tag> = channel.call { it.addTagToBook(BookId(bookId), name) }.map { it.toDomain() }

    override suspend fun removeTagFromBook(
        bookId: String,
        tagId: String,
    ): AppResult<Unit> = channel.call { it.removeTagFromBook(BookId(bookId), TagId(tagId)) }

    override suspend fun renameTag(
        tagId: String,
        newName: String,
    ): AppResult<Tag> = channel.call { it.renameTag(TagId(tagId), newName) }.map { it.toDomain() }

    override suspend fun deleteTag(tagId: String): AppResult<Unit> = channel.call { it.deleteTag(TagId(tagId)) }
}

// ── Mapping ───────────────────────────────────────────────────────────────────

/**
 * Map a Room [TagEntity] to the domain [Tag].
 */
private fun TagEntity.toDomain(): Tag =
    Tag(
        id = id,
        name = name,
        slug = slug,
    )

/**
 * Map the wire [com.calypsan.listenup.api.sync.Tag] to the domain [Tag].
 */
private fun com.calypsan.listenup.api.sync.Tag.toDomain(): Tag =
    Tag(
        id = id,
        name = name,
        slug = slug,
    )
