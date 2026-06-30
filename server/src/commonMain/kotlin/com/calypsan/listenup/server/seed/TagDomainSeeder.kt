package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.uuid.Uuid
import kotlin.time.Clock

private val logger = loggerFor<TagDomainSeeder>()

/**
 * Default tags seeded for the demo profile:
 *  - "Science Fiction" / "science-fiction"
 *  - "Fantasy" / "fantasy"
 *  - "Mystery" / "mystery"
 *  - "Non-Fiction" / "non-fiction"
 */
private data class SeedTag(
    val name: String,
    val slug: String,
)

private val DEMO_TAGS =
    listOf(
        SeedTag("Science Fiction", "science-fiction"),
        SeedTag("Fantasy", "fantasy"),
        SeedTag("Mystery", "mystery"),
        SeedTag("Non-Fiction", "non-fiction"),
    )

/**
 * Seeds a curated set of demo tags for the demo profile.
 *
 * Writes through [TagRepository.upsert] — the real domain write-path — so seeded rows
 * are indistinguishable from those created via the REST/RPC surface. Idempotency: if any
 * non-deleted tag row exists, the seeder considers itself already done; it will not partially
 * re-seed a database that already has tags. A failure response from [TagRepository.upsert]
 * is logged and swallowed so a second `seed()` call never throws.
 *
 * Runs at order 30 — after all scan-dependent seeders (the tags themselves are not
 * scan-derived, but they reference no other domain and can safely run last).
 */
internal class TagDomainSeeder(
    private val sql: ListenUpDatabase,
    private val tagRepository: TagRepository,
    private val clock: Clock = Clock.System,
) : DomainSeeder {
    override val domainName: String = "tags"

    override val order: Int = 30

    /** Returns true when at least one non-deleted tag row already exists. */
    override suspend fun isAlreadySeeded(): Boolean =
        suspendTransaction(sql) {
            sql.tagsQueries.hasAnyLive().executeAsOne()
        }

    override suspend fun seed() {
        val now = clock.now().toEpochMilliseconds()
        DEMO_TAGS.forEach { seedTag ->
            // Skip slugs that already exist — guards against a second seed() call and
            // partial-seed scenarios where only some tags were written before a crash.
            if (tagRepository.findBySlug(seedTag.slug) != null) {
                logger.info { "seed [$domainName]: tag '${seedTag.slug}' already present — skipping" }
                return@forEach
            }
            val tag =
                Tag(
                    id = Uuid.random().toString(),
                    name = seedTag.name,
                    slug = seedTag.slug,
                    revision = 0L,
                    updatedAt = now,
                    deletedAt = null,
                )
            when (val result = tagRepository.upsert(tag)) {
                is AppResult.Success -> {
                    logger.info { "seed [$domainName]: tag '${seedTag.name}' created id=${result.data.id}" }
                }

                is AppResult.Failure -> {
                    logger.warn { "seed [$domainName]: tag '${seedTag.name}' not created — ${result.error.code}" }
                }
            }
        }
    }
}
