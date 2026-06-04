package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.server.db.ShelvesTable
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.sync.ShelfRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/**
 * Seeds the demo profile's shelves for each demo user.
 *
 * Creates two demo shelves for the primary demo user:
 *  - a public shelf ("Favourites") — visible in discovery;
 *  - a private shelf ("Private Reads") — exercises the privacy filter in discovery.
 *
 * The shelves are written through [ShelfRepository.upsert], the domain's own write-path,
 * so seeded rows are indistinguishable from user-created ones.
 *
 * Idempotency check: [isAlreadySeeded] returns true when any demo-user shelf already
 * exists. A second [seed] call is therefore skipped by [SeedRunner].
 *
 * Runs at order 32 — just after [CollectionDomainSeeder] (order 31). Depends only on
 * the demo user existing (order 0), which is guaranteed to have run earlier.
 */
internal class ShelfDomainSeeder(
    private val db: Database,
    private val shelfRepo: ShelfRepository,
    private val clock: Clock = Clock.System,
) : DomainSeeder {
    override val domainName: String = "shelves"

    override val order: Int = 32

    /** True when the demo user already owns at least one shelf. */
    override suspend fun isAlreadySeeded(): Boolean {
        val demoUserId = demoUserId() ?: return false
        return suspendTransaction(db) {
            ShelvesTable
                .selectAll()
                .where { (ShelvesTable.userId eq demoUserId) and ShelvesTable.deletedAt.isNull() }
                .any()
        }
    }

    override suspend fun seed() {
        val ownerId = demoUserId()
        if (ownerId == null) {
            logger.info { "seed [$domainName]: demo user not found — deferring to next restart" }
            return
        }

        seedShelf(
            ownerId = ownerId,
            name = DEMO_PUBLIC_SHELF_NAME,
            description = "A collection of beloved audiobooks worth revisiting.",
            isPrivate = false,
        )
        seedShelf(
            ownerId = ownerId,
            name = DEMO_PRIVATE_SHELF_NAME,
            description = "Personal notes and private reading queue.",
            isPrivate = true,
        )
    }

    private suspend fun seedShelf(
        ownerId: String,
        name: String,
        description: String,
        isPrivate: Boolean,
    ) {
        val now = clock.now().toEpochMilliseconds()
        val payload =
            ShelfSyncPayload(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                isPrivate = isPrivate,
                revision = 0L,
                updatedAt = now,
                createdAt = now,
                deletedAt = null,
            )
        when (val result = shelfRepo.upsert(payload, userId = ownerId)) {
            is AppResult.Success -> {
                val visibility = if (isPrivate) "private" else "public"
                logger.info { "seed [$domainName]: '$name' ($visibility) created id=${result.data.id}" }
            }

            is AppResult.Failure -> {
                logger.warn { "seed [$domainName]: '$name' not created — ${result.error.code}" }
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

    companion object {
        internal const val DEMO_PUBLIC_SHELF_NAME = "Favourites"
        internal const val DEMO_PRIVATE_SHELF_NAME = "Private Reads"
    }
}
