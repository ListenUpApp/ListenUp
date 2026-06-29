package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.ActiveSessionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** How many demo books to seed live presence sessions for (at most). */
private const val DEMO_SESSION_COUNT = 2L

/**
 * Seeds live `active_sessions` (presence) rows for the [UserDomainSeeder.DEMO_EMAIL]
 * account, so a fresh demo instance immediately shows the social-presence surfaces —
 * "currently listening" and a book's reader list — populated without the developer
 * having to start playback on a second device.
 *
 * Records up to [DEMO_SESSION_COUNT] live sessions on the first available books in the
 * library. Presence is server-derived and never synced, so this seeder writes through
 * [ActiveSessionRepository.startOrRefresh] — the real domain write-path — leaving the
 * seeded rows indistinguishable from sessions started by `recordPosition`.
 *
 * Runs after [PlaybackPositionDomainSeeder] (order 10) and [ListeningEventDomainSeeder]
 * (order 20): like them it depends on the scanner having written at least one book. If
 * no books have been scanned yet when this seeder runs, no sessions are seeded and
 * [isAlreadySeeded] returns false on the next restart — at which point the scan should
 * be complete and presence will be seeded then.
 */
internal class ActiveSessionSeeder(
    private val sql: ListenUpDatabase,
    private val activeSessionRepository: ActiveSessionRepository,
) : DomainSeeder {
    override val domainName: String = "active_sessions"

    /**
     * Runs after [PlaybackPositionDomainSeeder] (order 10) and [ListeningEventDomainSeeder]
     * (order 20). Like them, presence rows carry a foreign-key dependency on books, so the
     * scanner must have written at least one book first.
     */
    override val order: Int = 30

    override suspend fun isAlreadySeeded(): Boolean {
        val userId = demoUserId() ?: return false
        return suspendTransaction(sql) {
            sql.activeSessionsQueries.hasAnyLiveForUser(user_id = userId).executeAsOne()
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

        bookIds.forEach { bookId ->
            activeSessionRepository.startOrRefresh(userId = userId, bookId = bookId)
            logger.info { "seed [$domainName]: live presence for book $bookId" }
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

    /** Returns the ids of up to [DEMO_SESSION_COUNT] non-deleted books, ordered by sort title. */
    private suspend fun availableBookIds(): List<String> =
        suspendTransaction(sql) {
            sql.booksQueries.selectLiveIdsBySortTitle(limit = DEMO_SESSION_COUNT).executeAsList()
        }
}
