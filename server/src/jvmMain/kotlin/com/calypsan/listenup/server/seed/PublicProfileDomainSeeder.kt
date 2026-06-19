package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.PublicProfilesTable
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

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
    private val db: Database,
    private val publicProfileMaintainer: PublicProfileMaintainer,
) : DomainSeeder {
    override val domainName: String = "public_profiles"

    override val order: Int = 33

    override suspend fun isAlreadySeeded(): Boolean {
        val demoUserId = demoUserId() ?: return false
        return suspendTransaction(db) {
            PublicProfilesTable
                .selectAll()
                .where { PublicProfilesTable.id eq demoUserId }
                .any()
        }
    }

    override suspend fun seed() {
        val userIds =
            suspendTransaction(db) {
                UserTable
                    .selectAll()
                    .where { UserTable.deletedAt.isNull() }
                    .map { it[UserTable.id].value }
            }
        userIds.forEach { publicProfileMaintainer.refresh(it) }
        logger.info { "seed [$domainName]: refreshed public_profiles for ${userIds.size} users" }
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
}
