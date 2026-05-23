package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.PlaybackPositionTable
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/** Spacing between the seeded positions' `lastPlayedAt` timestamps. */
private const val ONE_HOUR_MS = 60 * 60 * 1000L

/** How many demo books to seed positions for (at most). */
private const val DEMO_BOOK_COUNT = 3

/**
 * Varied position offsets (ms) — each "Continue Listening" card shows distinct progress.
 * Index matches the seeded book order.
 */
private val POSITION_OFFSETS_MS = listOf(
    5 * 60 * 1000L,   //  5 min — just started
    22 * 60 * 1000L,  // 22 min — well into it
    11 * 60 * 1000L,  // 11 min — somewhere in the middle
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
    private val db: Database,
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
        return suspendTransaction(db) {
            PlaybackPositionTable
                .selectAll()
                .where { (PlaybackPositionTable.userId eq userId) and PlaybackPositionTable.deletedAt.isNull() }
                .limit(1)
                .any()
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
        bookIds.take(DEMO_BOOK_COUNT).forEachIndexed { index, bookId ->
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
                is AppResult.Success ->
                    logger.info { "seed [$domainName]: recorded $positionMs ms for book $bookId" }

                is AppResult.Failure ->
                    logger.warn { "seed [$domainName]: failed for $bookId — ${result.error.code}" }
            }
        }
    }

    /** Returns the demo user's id string, or null if not yet in the database. */
    private suspend fun demoUserId(): String? =
        suspendTransaction(db) {
            UserTable
                .selectAll()
                .where { UserTable.email eq UserDomainSeeder.DEMO_EMAIL }
                .firstOrNull()
                ?.get(UserTable.id)
                ?.value
        }

    /** Returns the ids of up to [DEMO_BOOK_COUNT] non-deleted books, ordered by title. */
    private suspend fun availableBookIds(): List<String> =
        suspendTransaction(db) {
            BookTable
                .selectAll()
                .where { BookTable.deletedAt.isNull() }
                .orderBy(BookTable.sortTitle)
                .limit(DEMO_BOOK_COUNT)
                .map { it[BookTable.id] }
        }
}
