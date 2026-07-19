package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.TagSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.TagSlug
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
 * Tag reads ([listTags], [getTagBySlug], [listBooksForTag], [listTagsForBook]) are open to
 * any authenticated user. Tag mutations ([addTagToBook], [removeTagFromBook], [renameTag],
 * [deleteTag]) are gated on the per-user `canEdit` flag via [permissionPolicy]: ROOT/ADMIN
 * pass implicitly, a MEMBER passes iff their flag is set (fresh DB lookup per call). The
 * authenticated caller is resolved from [principal] — route handlers call [copyWith] to bind
 * it per-request; the Koin singleton carries an unscoped placeholder that yields no
 * principal, so an absent principal on a mutation is a wiring bug and is denied.
 */
internal class TagServiceImpl(
    private val tagRepository: TagRepository,
    private val bookTagRepository: BookTagRepository,
    private val reindexer: BookSearchReindexer,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val permissionPolicy: UserPermissionPolicy = UserPermissionPolicy(sql),
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : TagService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): TagServiceImpl =
        TagServiceImpl(tagRepository, bookTagRepository, reindexer, sql, clock, permissionPolicy, principal)

    /**
     * Content-metadata edits are gated on the per-user `canEdit` flag. ROOT/ADMIN pass
     * implicitly; a MEMBER passes iff their flag is set (fresh DB lookup per call). An
     * absent principal — a wiring bug, since route handlers always [copyWith] the
     * authenticated caller — is denied. Returns null when permitted; the denial otherwise.
     */
    private suspend fun requireCanEdit(): AppError? {
        val p = principal.current() ?: return AuthError.PermissionDenied()
        return permissionPolicy.requireCanEdit(p.userId, p.role)
    }

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
        // Batch the tag reads (one round-trip per 900-id chunk) instead of one findById
        // per junction. findByIds preserves order and skips absent/tombstoned ids, so the
        // result is identical to the prior per-row mapNotNull.
        val tags = tagRepository.findByIds(junctions.map { it.tagId })
        return AppResult.Success(tags)
    }

    override suspend fun getTagStats(tagId: TagId): AppResult<FacetStats> {
        val row = suspendTransaction(sql) { sql.bookTagsQueries.tagStats(tagId.value).executeAsOne() }
        return AppResult.Success(FacetStats(bookCount = row.book_count.toInt(), totalDurationMs = row.total_ms))
    }

    override suspend fun addTagToBook(
        bookId: BookId,
        name: String,
    ): AppResult<Tag> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // Validate name.
        val slug = TagSlug.normalize(name).getOrElse { return AppResult.Failure(it) }

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
                            id = Uuid.random().toString(),
                            name = name.trim(),
                            slug = slug,
                            revision = 0L,
                            updatedAt = clock.now().toEpochMilliseconds(),
                        )
                    tagRepository.upsert(newTag).getOrElse { return AppResult.Failure(it) }
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
        reindexer.reindexBook(bookId.value)

        return AppResult.Success(tag)
    }

    override suspend fun removeTagFromBook(
        bookId: BookId,
        tagId: TagId,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (!bookExists(bookId.value)) {
            return AppResult.Failure(TagError.BookNotFound())
        }
        if (tagRepository.findById(tagId.value) == null) {
            return AppResult.Failure(TagError.NotFound())
        }

        // softDelete is idempotent via the substrate — returns Failure(NotFound) if
        // already tombstoned, which we treat as success (caller's intent is satisfied).
        bookTagRepository.softDelete(bookId = bookId.value, tagId = tagId.value)
        reindexer.reindexBook(bookId.value)
        return AppResult.Success(Unit)
    }

    override suspend fun renameTag(
        tagId: TagId,
        newName: String,
    ): AppResult<Tag> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
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
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (tagRepository.findById(tagId.value) == null) {
            return AppResult.Failure(TagError.NotFound())
        }

        // Collect affected book IDs before tombstoning so we can reindex after.
        val affectedBookIds = bookTagRepository.findBookIdsForTag(tagId.value)

        // Cascade: tombstone all junctions, then the tag itself. Both are suspend repo
        // calls that each open their own SQLDelight transaction, so they run sequentially
        // (they cannot nest inside one another's non-suspend transaction body). Sequential
        // single-engine writes never contend for the lone SQLite write lock — matching the
        // GenreServiceImpl / ContributorServiceImpl cutover shape.
        bookTagRepository.softDeleteAllForTag(tagId.value)
        tagRepository.softDelete(tagId.value)

        // Reindex outside the cascade (FTS writes are separate).
        for (bookId in affectedBookIds) {
            reindexer.reindexBook(bookId)
        }

        return AppResult.Success(Unit)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun bookExists(bookId: String): Boolean =
        suspendTransaction(sql) { sql.booksQueries.existsLiveById(bookId).executeAsOne() }

    private suspend fun countLiveJunctionsForTag(tagId: String): Long =
        suspendTransaction(sql) { sql.bookTagsQueries.countLiveForTag(tagId).executeAsOne() }

    private suspend fun TagRepository.softDelete(tagId: String): AppResult<Unit> = softDelete(tagId, clientOpId = null)

    private suspend fun BookTagRepository.softDelete(
        bookId: String,
        tagId: String,
    ): AppResult<Unit> = softDelete(bookId, tagId, clientOpId = null)
}

/**
 * Builds a [TagService] over the given repositories, constructing the
 * [BookSearchReindexer] the rename/delete paths need internally.
 *
 * Public so cross-module test harnesses can mount a real [TagService] without piercing the
 * `internal` access on [TagServiceImpl]. Production wiring builds [TagServiceImpl] directly in
 * the Koin graph. Mirrors [createGenreService].
 */
fun createTagService(
    tagRepository: TagRepository,
    bookTagRepository: BookTagRepository,
    sqlDb: ListenUpDatabase,
    driver: SqlDriver,
): TagService =
    TagServiceImpl(
        tagRepository = tagRepository,
        bookTagRepository = bookTagRepository,
        reindexer =
            BookSearchReindexer(
                bookTagRepository = bookTagRepository,
                tagRepository = tagRepository,
                db = sqlDb,
                driver = driver,
            ),
        sql = sqlDb,
    )

/**
 * Scopes a [TagService] built by [createTagService] to [principal] for one request.
 * Public so cross-module test harnesses can bind the authenticated caller without piercing
 * the `internal` access on [TagServiceImpl.copyWith]. Production wiring calls
 * [TagServiceImpl.copyWith] directly in the RPC route. Mirrors [genreServiceScopedTo].
 */
fun tagServiceScopedTo(
    service: TagService,
    principal: PrincipalProvider,
): TagService = (service as TagServiceImpl).copyWith(principal)
