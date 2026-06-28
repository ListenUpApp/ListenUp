package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.MoodRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * A seeded mood — the canonical Audible affective vocabulary. The slug is the
 * dedupe anchor: an auto-created mood whose name normalizes to one of these slugs
 * collapses onto the seeded row rather than spawning a near-duplicate.
 */
private data class SeedMood(
    val name: String,
    val slug: String,
)

/**
 * The canonical Audible mood set. Unknown moods still auto-create via
 * [com.calypsan.listenup.server.services.BookMoodWriter]; this seed exists so the
 * common Audible vocabulary lands on stable, curated slugs.
 */
private val DEFAULT_MOODS: List<SeedMood> =
    listOf(
        SeedMood("Feel-Good", "feel-good"),
        SeedMood("Funny", "funny"),
        SeedMood("Witty", "witty"),
        SeedMood("Lighthearted", "lighthearted"),
        SeedMood("Inspiring", "inspiring"),
        SeedMood("Hopeful", "hopeful"),
        SeedMood("Heartwarming", "heartwarming"),
        SeedMood("Emotional", "emotional"),
        SeedMood("Romantic", "romantic"),
        SeedMood("Relaxing", "relaxing"),
        SeedMood("Calming", "calming"),
        SeedMood("Nostalgic", "nostalgic"),
        SeedMood("Dark", "dark"),
        SeedMood("Gritty", "gritty"),
        SeedMood("Tense", "tense"),
        SeedMood("Suspenseful", "suspenseful"),
        SeedMood("Scary", "scary"),
        SeedMood("Creepy", "creepy"),
        SeedMood("Mysterious", "mysterious"),
        SeedMood("Sad", "sad"),
        SeedMood("Melancholy", "melancholy"),
        SeedMood("Thought-Provoking", "thought-provoking"),
        SeedMood("Adventurous", "adventurous"),
        SeedMood("Epic", "epic"),
    )

/**
 * Seeds the canonical Audible mood vocabulary on a fresh installation. Writes through
 * [MoodRepository.upsert] — the real domain write path — so every seeded row is
 * indistinguishable from a curator creation. Idempotent: when any live mood already
 * exists, [isAlreadySeeded] returns `true` and [seed] is skipped; per-slug
 * find-before-write additionally guards a crashed-mid-seed re-run.
 *
 * Runs at order 50 — after [TagDomainSeeder] (30) and [GenreDomainSeeder] (40).
 * Moods reference no other domain, so order is structural rather than functional.
 */
internal class MoodDomainSeeder(
    private val sql: ListenUpDatabase,
    private val moodRepository: MoodRepository,
    private val clock: Clock = Clock.System,
) : DomainSeeder {
    override val domainName: String = "moods"

    override val order: Int = 50

    /** Returns true when at least one non-deleted mood row already exists. */
    override suspend fun isAlreadySeeded(): Boolean =
        suspendTransaction(sql) {
            sql.moodsQueries.hasAnyLive().executeAsOne()
        }

    override suspend fun seed() {
        val now = clock.now().toEpochMilliseconds()
        DEFAULT_MOODS.forEach { seedMood ->
            // Skip slugs that already exist — guards a second seed() call and partial-seed
            // scenarios where only some moods were written before a crash.
            if (moodRepository.findBySlug(seedMood.slug) != null) {
                logger.info { "seed [$domainName]: mood '${seedMood.slug}' already present — skipping" }
                return@forEach
            }
            val mood =
                Mood(
                    id = Uuid.random().toString(),
                    name = seedMood.name,
                    slug = seedMood.slug,
                    revision = 0L,
                    updatedAt = now,
                    deletedAt = null,
                )
            when (val result = moodRepository.upsert(mood)) {
                is AppResult.Success -> {
                    logger.info { "seed [$domainName]: mood '${seedMood.name}' created id=${result.data.id}" }
                }

                is AppResult.Failure -> {
                    logger.warn { "seed [$domainName]: mood '${seedMood.name}' not created — ${result.error.code}" }
                }
            }
        }
    }
}
