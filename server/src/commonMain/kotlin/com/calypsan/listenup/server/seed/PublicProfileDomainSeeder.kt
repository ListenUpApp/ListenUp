package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Refreshes the `public_profiles` projection for every seeded user so the dev
 * Discover leaderboard shows a populated multi-user roster. Runs after the user
 * and listening-event seeders (so stats already exist for users with events).
 * Idempotent: [PublicProfileMaintainer.refresh] always rewrites the full row.
 *
 * [isAlreadySeeded] returns true when the demo user already has a projection row,
 * which means this seeder ran (or the maintainer already fired for that user).
 * A second [SeedRunner] invocation therefore skips this seeder cleanly.
 *
 * Runs at order 33 — after [ShelfDomainSeeder] (32), so all user-facing
 * domain rows exist before the projection is built.
 */
internal class PublicProfileDomainSeeder(
    private val sql: ListenUpDatabase,
    private val publicProfileMaintainer: PublicProfileMaintainer,
) : DomainSeeder {
    override val domainName: String = "public_profiles"

    override val order: Int = 33

    override suspend fun isAlreadySeeded(): Boolean {
        val demoUserId = demoUserId() ?: return false
        return suspendTransaction(sql) {
            sql.publicProfilesQueries.existsById(id = demoUserId).executeAsOne()
        }
    }

    override suspend fun seed() {
        val userIds =
            suspendTransaction(sql) {
                sql.usersQueries.selectLiveUserIds().executeAsList()
            }
        userIds.forEach { publicProfileMaintainer.refresh(it) }
        logger.info { "seed [$domainName]: refreshed public_profiles for ${userIds.size} users" }
    }

    /** Returns the demo user's id string, or null if not yet in the database. */
    private suspend fun demoUserId(): String? =
        suspendTransaction(sql) {
            sql.usersQueries
                .selectByEmailNormalized(email_normalized = UserDomainSeeder.DEMO_EMAIL)
                .executeAsOneOrNull()
                ?.id
        }
}
