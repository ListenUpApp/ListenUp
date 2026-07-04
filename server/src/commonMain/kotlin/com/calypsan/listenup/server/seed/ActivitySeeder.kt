package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.logging.loggerFor

private val logger = loggerFor<ActivitySeeder>()

/**
 * Seeds a couple of social-activity rows for the [UserDomainSeeder.DEMO_EMAIL] account, so a fresh
 * demo instance shows a populated activity feed without a second device generating real events.
 *
 * Records one [ActivityType.FINISHED_BOOK] on the first available book plus one
 * [ActivityType.USER_JOINED] (non-book) activity, written through the real
 * [ActivityRecorder] write-path (the syncable repo) so the rows are indistinguishable from those
 * the hooks emit. The `user_joined` row is intentionally non-book so the demo feed exercises the
 * always-visible (ACL-exempt) activity surface alongside a book-bearing one.
 *
 * Runs after [ActiveSessionSeeder] (order 30): like the other playback seeders it depends on the
 * scanner having written at least one book. If no books have been scanned when this seeder runs,
 * *both* rows are deferred — [isAlreadySeeded] keys off any activity row for the demo user, so the
 * `user_joined` row is written only after the book-bearing row, ensuring a no-book first run seeds
 * nothing and [isAlreadySeeded] returns false on the next restart — by which point the scan should
 * be complete and both activities will be seeded then.
 */
internal class ActivitySeeder(
    private val sql: ListenUpDatabase,
    private val activityRecorder: ActivityRecorder,
) : DomainSeeder {
    override val domainName: String = "activities"

    /** Runs after [ActiveSessionSeeder] (order 30); the `finished_book` row depends on a book. */
    override val order: Int = 40

    override suspend fun isAlreadySeeded(): Boolean {
        val userId = demoUserId() ?: return false
        return suspendTransaction(sql) {
            sql.activitiesQueries.hasAnyForUser(user_id = userId).executeAsOne()
        }
    }

    override suspend fun seed() {
        val userId = demoUserId()
        if (userId == null) {
            logger.warn { "seed [$domainName]: demo user not found — skipping (UserDomainSeeder not yet run?)" }
            return
        }

        val bookId = firstAvailableBookId()
        if (bookId == null) {
            // Seed nothing until a book exists. isAlreadySeeded() keys off any activity row for the
            // demo user, so writing user_joined now would mark the domain done and permanently skip
            // the book activity. Deferring both rows lets the seeder genuinely re-run next restart.
            logger.info { "seed [$domainName]: no books scanned yet — deferring activity seeding to next restart" }
            return
        }
        activityRecorder.record(userId = userId, type = ActivityType.FINISHED_BOOK, bookId = bookId)
        logger.info { "seed [$domainName]: finished_book for book $bookId" }

        activityRecorder.record(userId = userId, type = ActivityType.USER_JOINED)
        logger.info { "seed [$domainName]: user_joined for demo user" }
    }

    /** Returns the demo user's id string, or null if not yet in the database. */
    private suspend fun demoUserId(): String? =
        suspendTransaction(sql) {
            sql.usersQueries
                .selectByEmailNormalized(email_normalized = UserDomainSeeder.DEMO_EMAIL)
                .executeAsOneOrNull()
                ?.id
        }

    /** Returns the id of the first non-deleted book (ordered by sort title), or null if none. */
    private suspend fun firstAvailableBookId(): String? =
        suspendTransaction(sql) {
            sql.booksQueries.selectLiveIdsBySortTitle(limit = 1L).executeAsOneOrNull()
        }
}
