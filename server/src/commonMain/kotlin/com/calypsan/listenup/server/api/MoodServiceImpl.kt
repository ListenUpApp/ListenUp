package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.MoodSlug
import kotlin.time.Clock
import kotlin.uuid.Uuid
import com.calypsan.listenup.server.auth.requirePermission

/**
 * [MoodService] implementation.
 *
 * Moods are server-wide (curator model, not user-scoped). The server keeps no search index,
 * so there is no reindex step on mutation — the sync firehose alone propagates changes
 * to clients.
 *
 * [getMoodStats] is scoped to the books the caller can reach. Edits ([addMoodToBook], [removeMoodFromBook]) are
 * gated on [Permission.EDIT_METADATA] via [permissionPolicy]:
 * ROOT/ADMIN pass implicitly, a MEMBER passes iff their flag is set (fresh DB lookup per call).
 * The authenticated caller is resolved from [principal] — route handlers call [copyWith] to
 * bind it per-request; the Koin singleton carries an unscoped placeholder that yields no
 * principal, so an absent principal on a mutation is a wiring bug and is denied.
 */
internal class MoodServiceImpl(
    private val moodRepository: MoodRepository,
    private val bookMoodRepository: BookMoodRepository,
    private val sql: ListenUpDatabase,
    /**
     * Book-level visibility — see [TagServiceImpl]'s identical parameter. Required, not defaulted:
     * mood stats and book edits touch BOOKS, so a construction site that forgot to wire this
     * would count and edit every book on the server for every member.
     */
    private val accessPolicy: BookAccessPolicy,
    private val clock: Clock = Clock.System,
    private val permissionPolicy: PermissionPolicy = PermissionPolicy(sql),
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : MoodService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): MoodServiceImpl =
        MoodServiceImpl(
            moodRepository = moodRepository,
            bookMoodRepository = bookMoodRepository,
            sql = sql,
            accessPolicy = accessPolicy,
            clock = clock,
            permissionPolicy = permissionPolicy,
            principal = principal,
        )

    /**
     * The caller's reachable book-id set, or null for ROOT/ADMIN. Mirrors [TagServiceImpl]'s
     * helper, including the fail-closed empty set for an absent principal.
     */
    private suspend fun accessibleBookIdFilter(): Set<String>? {
        val p = principal.current() ?: return emptySet()
        return accessPolicy.accessibleBookIds(p.userId.value, p.role)
    }

    override suspend fun getMoodStats(moodId: MoodId): AppResult<FacetStats> {
        // Count only books the caller can open, so a member is never told "12 books" and shown 9.
        // null = ROOT/ADMIN: every book, so the SQL aggregate answers directly.
        val accessible = accessibleBookIdFilter()
        val stats =
            suspendTransaction(sql) {
                if (accessible == null) {
                    val row = sql.bookMoodsQueries.moodStats(moodId.value).executeAsOne()
                    FacetStats(bookCount = row.book_count.toInt(), totalDurationMs = row.total_ms ?: 0L)
                } else {
                    val reachable =
                        sql.bookMoodsQueries
                            .moodBookDurations(moodId.value)
                            .executeAsList()
                            .filter { it.book_id in accessible }
                    FacetStats(bookCount = reachable.size, totalDurationMs = reachable.sumOf { it.duration_ms })
                }
            }
        return AppResult.Success(stats)
    }

    override suspend fun addMoodToBook(
        bookId: BookId,
        name: String,
    ): AppResult<Mood> {
        principal.requirePermission(permissionPolicy, Permission.EDIT_METADATA)?.let { return AppResult.Failure(it) }
        // Validate name.
        val slug = MoodSlug.normalize(name).getOrElse { return AppResult.Failure(it) }

        requireVisibleBook(bookId)?.let { return AppResult.Failure(it) }

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
        principal.requirePermission(permissionPolicy, Permission.EDIT_METADATA)?.let { return AppResult.Failure(it) }
        requireVisibleBook(bookId)?.let { return AppResult.Failure(it) }
        if (moodRepository.findById(moodId.value) == null) {
            return AppResult.Failure(MoodError.NotFound())
        }

        // softDelete is idempotent via the substrate — returns Failure(NotFound) if
        // already tombstoned, which we treat as success (caller's intent is satisfied).
        bookMoodRepository.softDelete(bookId = bookId.value, moodId = moodId.value)
        return AppResult.Success(Unit)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * The visibility half of a book-mood write, run after the permission gate: the book must be live
     * and reachable by the caller. A book the caller cannot see answers the same [MoodError.BookNotFound]
     * as one that does not exist — the choice [BookServiceImpl]'s `requireEditableBook` makes — so
     * the reply never confirms the id is a live book. Null when the write may proceed.
     */
    private suspend fun requireVisibleBook(bookId: BookId): MoodError? {
        val p = principal.current()
        val visible =
            p != null &&
                bookExists(bookId.value) &&
                accessPolicy.canAccess(p.userId.value, p.role, bookId.value)
        return if (visible) null else MoodError.BookNotFound(debugInfo = "bookId=${bookId.value}")
    }

    private suspend fun bookExists(bookId: String): Boolean =
        suspendTransaction(sql) { sql.booksQueries.existsLiveById(bookId).executeAsOne() }
}
