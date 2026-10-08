package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.social.BookReaderEntry
import com.calypsan.listenup.api.dto.social.BookReadership
import com.calypsan.listenup.api.dto.social.CurrentlyListeningSession
import com.calypsan.listenup.api.error.SocialError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.OpenToAllMembers
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.services.BookReadSource
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.homeTimeZone
import com.calypsan.listenup.server.sync.PublicProfileRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * [SocialService] implementation — the crown-jewel ACL surface.
 *
 * Resolves the authenticated caller from [principal] (never from request fields) and
 * filters every result through [BookAccessPolicy] for that viewer. The hard invariant:
 * a viewer must never learn that someone is listening to / reading a book they cannot
 * access.
 *
 * - [currentlyListening] returns one row per other user: whoever is listening **right now**
 *   first, then everyone else on the book they most recently played and did not finish. The
 *   fill exists because on a small server the live set is empty most of the time, and a
 *   section that silently hides itself is indistinguishable from a broken one. **Both halves
 *   pass through the same [BookAccessPolicy] access set** — a recent-fill book leaks a private
 *   library just as badly as a live session would, so a user whose newest unfinished book the
 *   caller cannot access is dropped entirely rather than shown on some other book. ROOT/ADMIN
 *   (unconstrained access set) see every row. A user with no live `public_profiles` identity is
 *   dropped — there is nobody to display.
 * - [bookReadership] returns `NotFound` when the caller cannot access the book — never
 *   revealing the book exists — and otherwise lists its full readership (including the
 *   caller): each reader's current progress (if reading) and their dated finish history. A
 *   Hardcover read within three calendar days of a ListenUp finish is the same listen: it is paired
 *   with that finish at request time ([pairWithHardcover]) and reported as the finish's
 *   `finishesAlsoOnHardcover`, never as a second row. Stored reads are untouched.
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
    private val sql: ListenUpDatabase,
    private val principal: PrincipalProvider,
    private val clock: Clock = Clock.System,
) : SocialService {
    override suspend fun currentlyListening(): AppResult<List<CurrentlyListeningSession>> {
        val caller = resolveCaller() ?: return noPrincipal()
        // accessibleBookIds returns null for ROOT/ADMIN — unconstrained, every book visible.
        // ONE access set gates BOTH halves: a recent-fill book leaks just as badly as a live one.
        val accessible = bookAccessPolicy.accessibleBookIds(caller.userId, caller.role)

        fun visible(bookId: String): Boolean = accessible == null || bookId in accessible

        val nowMs = clock.now().toEpochMilliseconds()

        // Live half — whoever has an open session right now, newest session per user.
        val live =
            activeSessions
                .listCurrentlyListening(
                    excludeUserId = caller.userId,
                    liveSince = nowMs - LIVE_WINDOW.inWholeMilliseconds,
                ).filter { visible(it.bookId) }
                .groupBy { it.userId }
                .values
                .map { perUser -> perUser.maxBy { it.startedAt } }
                .sortedByDescending { it.startedAt }

        // Recent half — everyone else, on the book they last played and did not finish. A user
        // already in the live half is skipped so nobody appears twice.
        val liveUserIds = live.map { it.userId }.toSet()
        val recent =
            playbackPositions
                .mostRecentUnfinishedPerUser(
                    excludeUserId = caller.userId,
                    playedSince = nowMs - RECENT_FILL_MAX_AGE.inWholeMilliseconds,
                ).filter { it.userId !in liveUserIds && visible(it.bookId) }
                .sortedByDescending { it.lastPlayedAt }

        // A user with no live public identity is dropped — there is nobody to display.
        val identities = publicProfiles.identities((live.map { it.userId } + recent.map { it.userId }).toSet())

        fun row(
            userId: String,
            bookId: String,
            lastActiveAtMs: Long,
            isLive: Boolean,
        ): CurrentlyListeningSession? =
            identities[userId]?.let { identity ->
                CurrentlyListeningSession(
                    userId = userId,
                    displayName = identity.displayName,
                    avatarType = identity.avatarType,
                    bookId = bookId,
                    lastActiveAtMs = lastActiveAtMs,
                    isLive = isLive,
                )
            }

        return AppResult.Success(
            live.mapNotNull { row(it.userId, it.bookId, it.startedAt, isLive = true) } +
                recent.mapNotNull { row(it.userId, it.bookId, it.lastPlayedAt, isLive = false) },
        )
    }

    @OpenToAllMembers(reason = "a read: who has read a book the caller can see")
    override suspend fun bookReadership(bookId: BookId): AppResult<BookReadership> {
        val caller = resolveCaller() ?: return noPrincipal()
        // Inaccessible book → NotFound, never revealing the book exists (unchanged ACL).
        if (!bookAccessPolicy.canAccess(caller.userId, caller.role, bookId.value)) {
            return AppResult.Failure(SocialError.NotFound())
        }
        val totalDuration = books.findById(bookId)?.totalDuration ?: 0L
        val readingSince = clock.now().toEpochMilliseconds() - READING_WINDOW.inWholeMilliseconds
        val inProgress = playbackPositions.listInProgressForBook(bookId.value, readingSince) // List<userId, positionMs>
        // Newest-first per user. Hardcover reads travel apart so every client can badge them.
        val (pulled, listened) =
            bookReads.finishesForBook(bookId.value).partition { it.source == BookReadSource.HARDCOVER }
        val finishesByUser = listened.groupBy { it.userId }
        val hardcoverByUser = pulled.groupBy { it.userId }

        val userIds = (inProgress.map { it.first } + finishesByUser.keys + hardcoverByUser.keys).toSet()
        val identities = publicProfiles.identities(userIds)

        val entries =
            userIds.mapNotNull { uid ->
                val identity = identities[uid] ?: return@mapNotNull null
                val listenedAt = finishesByUser[uid]?.map { it.finishedAt } ?: emptyList()
                // A listen logged both here and on Hardcover is one row, badged — not two.
                val paired =
                    pairWithHardcover(
                        own = listenedAt,
                        hardcover = hardcoverByUser[uid]?.map { it.finishedAt } ?: emptyList(),
                        zone = sql.homeTimeZone(uid),
                    )
                val positionMs = inProgress.firstOrNull { it.first == uid }?.second
                val pct =
                    positionMs?.let {
                        if (totalDuration > 0) (it * 100 / totalDuration).toInt().coerceIn(0, 100) else null
                    }
                BookReaderEntry(
                    userId = uid,
                    displayName = identity.displayName,
                    avatarType = identity.avatarType,
                    currentProgressPct = pct,
                    finishes = listenedAt,
                    hardcoverFinishes = paired.hardcoverOnly,
                    finishesAlsoOnHardcover = paired.alsoOnHardcover,
                )
            }
        // Reading-first, then most-recent finish (either kind) desc.
        val ordered =
            entries.sortedWith(
                compareByDescending<BookReaderEntry> { it.currentProgressPct != null }
                    .thenByDescending {
                        maxOf(
                            it.finishes.firstOrNull() ?: Long.MIN_VALUE,
                            it.hardcoverFinishes.firstOrNull() ?: Long.MIN_VALUE,
                        )
                    },
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
            sql = sql,
            principal = principal,
            clock = clock,
        )

    /** The resolved caller: their user id and contract role (the role [BookAccessPolicy] speaks). */
    private data class Caller(
        val userId: String,
        val role: UserRole,
    )

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(SocialError.NotFound())

    private companion object {
        /**
         * How recently a presence row must have been refreshed to count as "listening now".
         *
         * `PlaybackManagerImpl` persists position every 10 seconds of content and `recordPosition`
         * refreshes the row, so five minutes absorbs ~30 missed reports plus sync-drain latency. It is
         * deliberately far shorter than `ActiveSessionCleanupTask`'s 30-minute reclaim: that sweep
         * exists to free rows, not to define truth.
         */
        val LIVE_WINDOW = 5.minutes

        /**
         * How recently someone must have played a book for it to fill the roster.
         *
         * The fill exists so a small server's section is not empty most of the time. A months-old row
         * does not serve that: it reads as broken rather than quiet, which is the very failure the
         * fill was added to prevent. Fourteen days keeps a slow server populated while still implying
         * current activity.
         */
        val RECENT_FILL_MAX_AGE = 14.days

        /**
         * How recently someone must have played a book to count as reading it in its readers row.
         *
         * Longer than [RECENT_FILL_MAX_AGE]: that asks "what are people listening to lately", this
         * asks "is this person still reading this book" — a listener on a slow month is. A book left
         * at 40% in March is not, and showing it as "reading" forever says something false about a
         * friend. Finishes are unaffected; they are history, not a claim about now.
         */
        val READING_WINDOW = 30.days
    }
}
