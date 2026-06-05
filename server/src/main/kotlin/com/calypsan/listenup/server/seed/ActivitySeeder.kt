package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.db.ActivitiesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.ActivityRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/**
 * Seeds a couple of social-activity rows for the [UserDomainSeeder.DEMO_EMAIL] account, so a fresh
 * demo instance shows a populated activity feed without a second device generating real events.
 *
 * Records one [ActivityType.FINISHED_BOOK] on the first available book plus one
 * [ActivityType.USER_JOINED] (non-book) activity, written through the real
 * [ActivityRepository.record] write-path so the rows are indistinguishable from those the hooks
 * emit. The `user_joined` row is intentionally non-book so the demo feed exercises the
 * always-visible (ACL-exempt) activity surface alongside a book-bearing one.
 *
 * Runs after [ActiveSessionSeeder] (order 30): like the other playback seeders it depends on the
 * scanner having written at least one book. If no books have been scanned when this seeder runs,
 * the `finished_book` is skipped and [isAlreadySeeded] returns false on the next restart — by which
 * point the scan should be complete and the book activity will be seeded then.
 */
internal class ActivitySeeder(
    private val db: Database,
    private val activityRepository: ActivityRepository,
) : DomainSeeder {
    override val domainName: String = "activities"

    /** Runs after [ActiveSessionSeeder] (order 30); the `finished_book` row depends on a book. */
    override val order: Int = 40

    override suspend fun isAlreadySeeded(): Boolean {
        val userId = demoUserId() ?: return false
        return suspendTransaction(db) {
            ActivitiesTable
                .selectAll()
                .where { ActivitiesTable.userId eq userId }
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

        activityRepository.record(userId = userId, type = ActivityType.USER_JOINED)
        logger.info { "seed [$domainName]: user_joined for demo user" }

        val bookId = firstAvailableBookId()
        if (bookId == null) {
            logger.info { "seed [$domainName]: no books scanned yet — deferring the book activity to next restart" }
            return
        }
        activityRepository.record(userId = userId, type = ActivityType.FINISHED_BOOK, bookId = bookId)
        logger.info { "seed [$domainName]: finished_book for book $bookId" }
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

    /** Returns the id of the first non-deleted book (ordered by sort title), or null if none. */
    private suspend fun firstAvailableBookId(): String? =
        suspendTransaction(db) {
            BookTable
                .selectAll()
                .where { BookTable.deletedAt.isNull() }
                .orderBy(BookTable.sortTitle)
                .limit(1)
                .firstOrNull()
                ?.get(BookTable.id)
        }
}
