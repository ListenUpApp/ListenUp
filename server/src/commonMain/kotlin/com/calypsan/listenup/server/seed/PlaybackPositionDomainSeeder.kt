package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock

private val logger = loggerFor<PlaybackPositionDomainSeeder>()

/** Spacing between the seeded positions' `lastPlayedAt` timestamps. */
private const val ONE_HOUR_MS = 60 * 60 * 1000L

/** How many demo books to seed positions for (at most). */
private const val DEMO_BOOK_COUNT = 3L

/**
 * Varied position offsets (ms) — each "Continue Listening" card shows distinct progress.
 * Index matches the seeded book order.
 */
private val POSITION_OFFSETS_MS =
    listOf(
        5 * 60 * 1000L, //  5 min — just started
        22 * 60 * 1000L, // 22 min — well into it
        11 * 60 * 1000L, // 11 min — somewhere in the middle
    )

/**
 * Seeds demo playback positions for the [UserDomainSeeder.DEMO_EMAIL] account.
 *
 * Records a couple of in-progress positions on the first available books in the
 * library so a fresh demo instance immediately shows "Continue Listening" content
 * without the developer having to manually play anything.
 *
 * Runs after [UserDomainSeeder] (order 0) and after the initial library scan
 * (which runs concurrently with the seed runner). If no books have been scanned
 * yet when this seeder runs, no positions are seeded and `isAlreadySeeded` will
 * return false on the next restart — at which point the scan should be complete
 * and positions will be seeded then.
 *
 * Writes through [PlaybackPositionRepository.recordPosition] — the real domain
 * write-path — so seeded rows are indistinguishable from real ones.
 */
internal class PlaybackPositionDomainSeeder(
    private val sql: ListenUpDatabase,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val clock: Clock = Clock.System,
) : DomainSeeder {
    override val domainName: String = "playback_positions"

    /**
     * Runs after [UserDomainSeeder] (order 0). Order 10 gives the initial library
     * scan enough runway to write the first batch of books before this seeder
     * queries them. Books are written by the scanner, which launches concurrently
     * with the seed runner.
     */
    override val order: Int = 10

    override suspend fun isAlreadySeeded(): Boolean {
        val userId = demoUserId() ?: return false
        return suspendTransaction(sql) {
            sql.playbackPositionsQueries.hasAnyLiveForUser(userId = userId).executeAsOne()
        }
    }

    override suspend fun seed() {
        val userId = demoUserId()
        if (userId == null) {
            logger.warn { "seed [$domainName]: demo user not found — skipping (UserDomainSeeder not yet run?)" }
            return
        }

        val bookIds = availableBookIds()
        if (bookIds.isEmpty()) {
            logger.info { "seed [$domainName]: no books scanned yet — deferring to next restart" }
            return
        }

        val now = clock.now().toEpochMilliseconds()
        bookIds.take(DEMO_BOOK_COUNT.toInt()).forEachIndexed { index, bookId ->
            val positionMs = POSITION_OFFSETS_MS[index]
            when (
                val result =
                    playbackPositionRepository.recordPosition(
                        userId = userId,
                        bookId = bookId,
                        positionMs = positionMs,
                        lastPlayedAt = now - (index * ONE_HOUR_MS),
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
            ) {
                is AppResult.Success -> {
                    logger.info { "seed [$domainName]: recorded $positionMs ms for book $bookId" }
                }

                is AppResult.Failure -> {
                    logger.warn { "seed [$domainName]: failed for $bookId — ${result.error.code}" }
                }
            }
        }
    }

    /** Returns the demo user's id string, or null if not yet in the database. */
    private suspend fun demoUserId(): String? =
        suspendTransaction(sql) {
            sql.usersQueries
                .selectByEmailNormalized(email_normalized = UserDomainSeeder.DEMO_EMAIL)
                .executeAsOneOrNull()
                ?.id
        }

    /** Returns the ids of up to [DEMO_BOOK_COUNT] non-deleted books, ordered by title. */
    private suspend fun availableBookIds(): List<String> =
        suspendTransaction(sql) {
            sql.booksQueries.selectLiveIdsBySortTitle(limit = DEMO_BOOK_COUNT).executeAsList()
        }
}
