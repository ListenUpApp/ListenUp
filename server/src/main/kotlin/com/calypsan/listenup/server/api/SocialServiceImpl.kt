package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.social.BookReaderEntry
import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.PublicProfileRepository

/**
 * [SocialService] implementation — the crown-jewel ACL surface.
 *
 * Resolves the authenticated caller from [principal] (never from request fields) and
 * filters every result through [BookAccessPolicy] for that viewer. The hard invariant:
 * a viewer must never learn that someone is listening to / reading a book they cannot
 * access.
 *
 * - [currentlyListening] returns other users' live sessions, keeping only those on books
 *   the caller can access. ROOT/ADMIN (unconstrained access set) see every session.
 *   Sessions whose user has no live `public_profiles` identity are dropped — there is no
 *   one to display.
 * - [bookReadership] returns `NotFound` when the caller cannot access the book — never
 *   revealing the book exists — and otherwise lists its full readership (including the
 *   caller): each reader's current progress (if reading) and their dated finish history.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal;
 * the Koin singleton carries an unscoped placeholder [PrincipalProvider] that throws
 * (fail-loud) if ever invoked, so a route that forgets to [copyWith] surfaces as a
 * guarded `InternalError` rather than silently leaking unscoped data.
 */
internal class SocialServiceImpl(
    private val activeSessions: ActiveSessionRepository,
    private val bookAccessPolicy: BookAccessPolicy,
    private val publicProfiles: PublicProfileRepository,
    private val playbackPositions: PlaybackPositionRepository,
    private val bookReads: BookReadsRepository,
    private val books: BookRepository,
    private val principal: PrincipalProvider,
) : SocialService {
    override suspend fun currentlyListening(): AppResult<List<CurrentlyListeningSession>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val rows = activeSessions.listCurrentlyListening(excludeUserId = caller.userId)
        // accessibleBookIds returns null for ROOT/ADMIN — unconstrained, every book visible.
        val accessible = bookAccessPolicy.accessibleBookIds(caller.userId, caller.role)
        val visible = if (accessible == null) rows else rows.filter { it.bookId in accessible }
        val identities = publicProfiles.identities(visible.map { it.userId }.toSet())
        return AppResult.Success(
            visible.mapNotNull { row ->
                val identity = identities[row.userId] ?: return@mapNotNull null
                CurrentlyListeningSession(
                    userId = row.userId,
                    displayName = identity.displayName,
                    avatarType = identity.avatarType,
                    bookId = row.bookId,
                    startedAtMs = row.startedAt,
                )
            },
        )
    }

    override suspend fun bookReadership(bookId: BookId): AppResult<BookReadership> {
        val caller = resolveCaller() ?: return noPrincipal()
        // Inaccessible book → NotFound, never revealing the book exists (unchanged ACL).
        if (!bookAccessPolicy.canAccess(caller.userId, caller.role, bookId.value)) {
            return AppResult.Failure(SocialError.NotFound())
        }
        val totalDuration = books.findById(bookId)?.totalDuration ?: 0L
        val inProgress = playbackPositions.listInProgressForBook(bookId.value) // List<userId, positionMs>
        val finishesByUser = bookReads.finishesForBook(bookId.value).groupBy { it.userId } // newest-first per user

        val userIds = (inProgress.map { it.first } + finishesByUser.keys).toSet()
        val identities = publicProfiles.identities(userIds)

        val entries =
            userIds.mapNotNull { uid ->
                val identity = identities[uid] ?: return@mapNotNull null
                val positionMs = inProgress.firstOrNull { it.first == uid }?.second
                val pct =
                    positionMs?.let {
                        if (totalDuration > 0) ((it * 100) / totalDuration).toInt().coerceIn(0, 100) else null
                    }
                BookReaderEntry(
                    userId = uid,
                    displayName = identity.displayName,
                    avatarType = identity.avatarType,
                    currentProgressPct = pct,
                    finishes = finishesByUser[uid]?.map { it.finishedAt } ?: emptyList(),
                )
            }
        // Reading-first, then most-recent finish desc.
        val ordered =
            entries.sortedWith(
                compareByDescending<BookReaderEntry> { it.currentProgressPct != null }
                    .thenByDescending { it.finishes.firstOrNull() ?: Long.MIN_VALUE },
            )
        return AppResult.Success(BookReadership(readers = ordered))
    }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): SocialServiceImpl =
        SocialServiceImpl(
            activeSessions = activeSessions,
            bookAccessPolicy = bookAccessPolicy,
            publicProfiles = publicProfiles,
            playbackPositions = playbackPositions,
            bookReads = bookReads,
            books = books,
            principal = principal,
        )

    /** The resolved caller: their user id and contract role (the role [BookAccessPolicy] speaks). */
    private data class Caller(
        val userId: String,
        val role: UserRole,
    )

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(SocialError.NotFound())
}
