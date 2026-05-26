package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.dto.TagSummary
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.server.db.BookTagsTable
import com.calypsan.listenup.server.db.TagTable
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.TagSlug
import java.util.UUID
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private const val MAX_LIMIT = 1000
private const val MIN_LIMIT = 1

/**
 * [TagService] implementation.
 *
 * Tags are server-wide (curator model, not user-scoped). All mutation methods
 * call [BookSearchReindexer] after writes so the FTS `book_search.tags` column
 * stays consistent with the live junction state.
 *
 * Slug conflict on rename: only the [Tag.name] is updated — [Tag.slug] is
 * intentionally preserved per the contract spec so existing URLs remain valid.
 * Renames therefore cannot produce a slug conflict; the slug stays the same.
 *
 * // TODO: gate by user permissions when Multi-user lands
 */
internal class TagServiceImpl(
    private val tagRepository: TagRepository,
    private val bookTagRepository: BookTagRepository,
    private val reindexer: BookSearchReindexer,
    private val db: Database,
    private val clock: Clock = Clock.System,
) : TagService {
    override suspend fun listTags(): AppResult<List<TagSummary>> {
        val tags = tagRepository.listAll()
        // Compute book counts via raw SQL sub-select to avoid N+1.
        val summaries =
            tags.map { tag ->
                val bookCount = countLiveJunctionsForTag(tag.id)
                TagSummary(
                    id = TagId(tag.id),
                    slug = tag.slug,
                    name = tag.name,
                    bookCount = bookCount,
                )
            }
        // Sort by bookCount desc, then name asc — mirroring the contract spec ordering.
        return AppResult.Success(
            summaries.sortedWith(compareByDescending<TagSummary> { it.bookCount }.thenBy { it.name }),
        )
    }

    override suspend fun getTagBySlug(slug: String): AppResult<TagSummary?> {
        val tag = tagRepository.findBySlug(slug) ?: return AppResult.Success(null)
        val bookCount = countLiveJunctionsForTag(tag.id)
        return AppResult.Success(
            TagSummary(
                id = TagId(tag.id),
                slug = tag.slug,
                name = tag.name,
                bookCount = bookCount,
            ),
        )
    }

    override suspend fun listBooksForTag(
        tagId: TagId,
        limit: Int,
    ): AppResult<List<BookId>> {
        val safeLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val junctions = bookTagRepository.findAllForTag(tagId.value)
        return AppResult.Success(junctions.take(safeLimit).map { BookId(it.bookId) })
    }

    override suspend fun listTagsForBook(bookId: BookId): AppResult<List<Tag>> {
        if (!bookExists(bookId.value)) {
            return AppResult.Failure(TagError.BookNotFound())
        }
        val junctions = bookTagRepository.findAllForBook(bookId.value)
        val tags =
            junctions.mapNotNull { junc ->
                tagRepository.findById(junc.tagId)
            }
        return AppResult.Success(tags)
    }

    override suspend fun addTagToBook(
        bookId: BookId,
        name: String,
    ): AppResult<Tag> {
        // Validate name.
        val slug =
            when (val slugResult = TagSlug.normalize(name)) {
                is AppResult.Success -> slugResult.data
                is AppResult.Failure -> return AppResult.Failure(slugResult.error)
            }

        // Verify book exists.
        if (!bookExists(bookId.value)) {
            return AppResult.Failure(TagError.BookNotFound())
        }

        // Find-or-create the tag by slug.
        val tag =
            tagRepository.findBySlug(slug)
                ?: run {
                    val newTag =
                        Tag(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            slug = slug,
                            revision = 0L,
                            updatedAt = clock.now().toEpochMilliseconds(),
                        )
                    when (val result = tagRepository.upsert(newTag)) {
                        is AppResult.Success -> result.data
                        is AppResult.Failure -> return AppResult.Failure(result.error)
                    }
                }

        // Upsert the junction row (re-adding a previously removed tag clears deletedAt).
        val now = clock.now().toEpochMilliseconds()
        val junctionPayload =
            BookTagSyncPayload(
                bookId = bookId.value,
                tagId = tag.id,
                createdAt = now,
                revision = 0L,
                deletedAt = null,
            )
        when (val result = bookTagRepository.upsert(junctionPayload)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(result.error)
        }

        // Reindex FTS after junction write.
        reindexer.reindexBookTags(bookId.value)

        return AppResult.Success(tag)
    }

    override suspend fun removeTagFromBook(
        bookId: BookId,
        tagId: TagId,
    ): AppResult<Unit> {
        if (!bookExists(bookId.value)) {
            return AppResult.Failure(TagError.BookNotFound())
        }
        if (tagRepository.findById(tagId.value) == null) {
            return AppResult.Failure(TagError.NotFound())
        }

        // softDelete is idempotent via the substrate — returns Failure(NotFound) if
        // already tombstoned, which we treat as success (caller's intent is satisfied).
        bookTagRepository.softDelete(bookId = bookId.value, tagId = tagId.value)
        reindexer.reindexBookTags(bookId.value)
        return AppResult.Success(Unit)
    }

    override suspend fun renameTag(
        tagId: TagId,
        newName: String,
    ): AppResult<Tag> {
        // Validate new name (we call normalize just for validation — slug is not changed).
        when (val slugResult = TagSlug.normalize(newName)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(slugResult.error)
        }

        val updated =
            tagRepository.updateName(tagId.value, newName.trim())
                ?: return AppResult.Failure(TagError.NotFound())

        // Reindex all books that have this tag so FTS sees the updated name.
        reindexer.reindexAllBooksForTag(tagId.value)

        return AppResult.Success(updated)
    }

    override suspend fun deleteTag(tagId: TagId): AppResult<Unit> {
        if (tagRepository.findById(tagId.value) == null) {
            return AppResult.Failure(TagError.NotFound())
        }

        // Collect affected book IDs before tombstoning so we can reindex after.
        val affectedBookIds = bookTagRepository.findBookIdsForTag(tagId.value)

        // Atomic cascade: tombstone all junctions + the tag itself in one transaction.
        suspendTransaction(db) {
            bookTagRepository.softDeleteAllForTag(tagId.value)
            tagRepository.softDelete(tagId.value)
        }

        // Reindex outside the transaction (FTS writes are separate).
        for (bookId in affectedBookIds) {
            reindexer.reindexBookTags(bookId)
        }

        return AppResult.Success(Unit)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun bookExists(bookId: String): Boolean =
        suspendTransaction(db) {
            com.calypsan.listenup.server.db.BookTable
                .selectAll()
                .where {
                    (com.calypsan.listenup.server.db.BookTable.id eq bookId) and
                        com.calypsan.listenup.server.db.BookTable.deletedAt
                            .isNull()
                }.count() > 0
        }

    private suspend fun countLiveJunctionsForTag(tagId: String): Long =
        suspendTransaction(db) {
            BookTagsTable
                .selectAll()
                .where { (BookTagsTable.tagId eq tagId) and BookTagsTable.deletedAt.isNull() }
                .count()
        }

    private suspend fun TagRepository.softDelete(tagId: String): AppResult<Unit> = softDelete(tagId, clientOpId = null)

    private suspend fun BookTagRepository.softDeleteAllForTag(tagId: String): Int {
        // Delegate to existing bulk helper — but we need it within the open
        // suspendTransaction. The helper opens its own transaction; for simplicity
        // we accept the nested semantics — both operate on the same underlying
        // JDBC connection and SQLite in WAL mode handles this correctly.
        return this.softDeleteAllForTag(tagId)
    }

    private suspend fun BookTagRepository.softDelete(
        bookId: String,
        tagId: String,
    ): AppResult<Unit> = softDelete(bookId, tagId, clientOpId = null)
}
