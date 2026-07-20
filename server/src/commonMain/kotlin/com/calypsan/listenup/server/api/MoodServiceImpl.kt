package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.MoodSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.MoodSlug
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MAX_LIMIT = 1000
private const val MIN_LIMIT = 1

/**
 * [MoodService] implementation.
 *
 * Moods are server-wide (curator model, not user-scoped). Unlike tags, moods are not
 * part of the `book_search` FTS index, so there is no reindex step on mutation — the
 * sync firehose alone propagates changes to clients.
 *
 * Slug conflict on rename: only the [Mood.name] is updated — [Mood.slug] is
 * intentionally preserved per the contract spec so existing URLs remain valid.
 * Renames therefore cannot produce a slug conflict; the slug stays the same.
 *
 * Mood reads ([listMoods], [getMoodBySlug], [listBooksForMood], [listMoodsForBook]) are open
 * to any authenticated user. Mood mutations ([addMoodToBook], [removeMoodFromBook],
 * [renameMood], [deleteMood]) are gated on the per-user `canEdit` flag via [permissionPolicy]:
 * ROOT/ADMIN pass implicitly, a MEMBER passes iff their flag is set (fresh DB lookup per call).
 * The authenticated caller is resolved from [principal] — route handlers call [copyWith] to
 * bind it per-request; the Koin singleton carries an unscoped placeholder that yields no
 * principal, so an absent principal on a mutation is a wiring bug and is denied.
 */
internal class MoodServiceImpl(
    private val moodRepository: MoodRepository,
    private val bookMoodRepository: BookMoodRepository,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val permissionPolicy: UserPermissionPolicy = UserPermissionPolicy(sql),
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : MoodService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): MoodServiceImpl =
        MoodServiceImpl(moodRepository, bookMoodRepository, sql, clock, permissionPolicy, principal)

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

    override suspend fun listMoods(): AppResult<List<MoodSummary>> {
        val moods = moodRepository.listAll()
        val summaries =
            moods.map { mood ->
                val bookCount = countLiveJunctionsForMood(mood.id)
                MoodSummary(
                    id = MoodId(mood.id),
                    slug = mood.slug,
                    name = mood.name,
                    bookCount = bookCount,
                )
            }
        // Sort by bookCount desc, then name asc — mirroring the tag-summary ordering.
        return AppResult.Success(
            summaries.sortedWith(compareByDescending<MoodSummary> { it.bookCount }.thenBy { it.name }),
        )
    }

    override suspend fun getMoodBySlug(slug: String): AppResult<MoodSummary?> {
        val mood = moodRepository.findBySlug(slug) ?: return AppResult.Success(null)
        val bookCount = countLiveJunctionsForMood(mood.id)
        return AppResult.Success(
            MoodSummary(
                id = MoodId(mood.id),
                slug = mood.slug,
                name = mood.name,
                bookCount = bookCount,
            ),
        )
    }

    override suspend fun listBooksForMood(
        moodId: MoodId,
        limit: Int,
    ): AppResult<List<BookId>> {
        val safeLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val junctions = bookMoodRepository.findAllForMood(moodId.value)
        return AppResult.Success(junctions.take(safeLimit).map { BookId(it.bookId) })
    }

    override suspend fun listMoodsForBook(bookId: BookId): AppResult<List<Mood>> {
        if (!bookExists(bookId.value)) {
            return AppResult.Failure(MoodError.BookNotFound())
        }
        val junctions = bookMoodRepository.findAllForBook(bookId.value)
        // Batch the mood reads (one round-trip per 900-id chunk) instead of one findById
        // per junction. findByIds preserves order and skips absent/tombstoned ids, so the
        // result is identical to the prior per-row mapNotNull.
        val moods = moodRepository.findByIds(junctions.map { it.moodId })
        return AppResult.Success(moods)
    }

    override suspend fun getMoodStats(moodId: MoodId): AppResult<FacetStats> {
        val row = suspendTransaction(sql) { sql.bookMoodsQueries.moodStats(moodId.value).executeAsOne() }
        return AppResult.Success(FacetStats(bookCount = row.book_count.toInt(), totalDurationMs = row.total_ms))
    }

    override suspend fun addMoodToBook(
        bookId: BookId,
        name: String,
    ): AppResult<Mood> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // Validate name.
        val slug = MoodSlug.normalize(name).getOrElse { return AppResult.Failure(it) }

        // Verify book exists.
        if (!bookExists(bookId.value)) {
            return AppResult.Failure(MoodError.BookNotFound())
        }

        // Find-or-create the mood by slug.
        val mood =
            moodRepository.findBySlug(slug)
                ?: run {
                    val newMood =
                        Mood(
                            id = Uuid.random().toString(),
                            name = name.trim(),
                            slug = slug,
                            revision = 0L,
                            updatedAt = clock.now().toEpochMilliseconds(),
                        )
                    moodRepository.upsert(newMood).getOrElse { return AppResult.Failure(it) }
                }

        // Upsert the junction row (re-adding a previously removed mood clears deletedAt).
        val now = clock.now().toEpochMilliseconds()
        val junctionPayload =
            BookMoodSyncPayload(
                id = Uuid.random().toString(),
                bookId = bookId.value,
                moodId = mood.id,
                createdAt = now,
                revision = 0L,
                deletedAt = null,
            )
        when (val result = bookMoodRepository.upsert(junctionPayload)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(result.error)
        }

        return AppResult.Success(mood)
    }

    override suspend fun removeMoodFromBook(
        bookId: BookId,
        moodId: MoodId,
    ): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (!bookExists(bookId.value)) {
            return AppResult.Failure(MoodError.BookNotFound())
        }
        if (moodRepository.findById(moodId.value) == null) {
            return AppResult.Failure(MoodError.NotFound())
        }

        // softDelete is idempotent via the substrate — returns Failure(NotFound) if
        // already tombstoned, which we treat as success (caller's intent is satisfied).
        bookMoodRepository.softDelete(bookId = bookId.value, moodId = moodId.value)
        return AppResult.Success(Unit)
    }

    override suspend fun renameMood(
        moodId: MoodId,
        newName: String,
    ): AppResult<Mood> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        // Validate new name (we call normalize just for validation — slug is not changed).
        when (val slugResult = MoodSlug.normalize(newName)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return AppResult.Failure(slugResult.error)
        }

        val updated =
            moodRepository.updateName(moodId.value, newName.trim())
                ?: return AppResult.Failure(MoodError.NotFound())

        return AppResult.Success(updated)
    }

    override suspend fun deleteMood(moodId: MoodId): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        if (moodRepository.findById(moodId.value) == null) {
            return AppResult.Failure(MoodError.NotFound())
        }

        // Cascade: tombstone all junctions, then the mood itself. Both are suspend repo
        // calls that each open their own SQLDelight transaction, so they run sequentially
        // (they cannot nest inside one another's non-suspend transaction body). Sequential
        // single-engine writes never contend for the lone SQLite write lock — matching the
        // GenreServiceImpl / ContributorServiceImpl cutover shape.
        bookMoodRepository.softDeleteAllForMood(moodId.value)
        moodRepository.softDelete(moodId.value)

        return AppResult.Success(Unit)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun bookExists(bookId: String): Boolean =
        suspendTransaction(sql) { sql.booksQueries.existsLiveById(bookId).executeAsOne() }

    private suspend fun countLiveJunctionsForMood(moodId: String): Long =
        suspendTransaction(sql) { sql.bookMoodsQueries.countLiveForMood(moodId).executeAsOne() }

    private suspend fun MoodRepository.softDelete(moodId: String): AppResult<Unit> =
        softDelete(moodId, clientOpId = null)
}
