package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = loggerFor<GenreDomainSeeder>()

/**
 * Seeded genre node — the curator-controlled default taxonomy.
 */
private data class GenreSeed(
    val name: String,
    val slug: String,
    val children: List<GenreSeed> = emptyList(),
)

/**
 * Default genre tree.
 *
 * Three top-level roots: Fiction, Non-Fiction, Children's & Young Adult. Mostly
 * two levels deep; a few branches reach depth 3.
 */
private val DEFAULT_GENRES: List<GenreSeed> =
    listOf(
        GenreSeed(
            name = "Fiction",
            slug = "fiction",
            children =
                listOf(
                    GenreSeed(
                        name = "Fantasy",
                        slug = "fantasy",
                        children =
                            listOf(
                                GenreSeed("Epic Fantasy", "epic-fantasy"),
                                GenreSeed("Urban Fantasy", "urban-fantasy"),
                                GenreSeed("Dark Fantasy", "dark-fantasy"),
                                GenreSeed("LitRPG", "litrpg"),
                                GenreSeed("Progression Fantasy", "progression-fantasy"),
                                GenreSeed("Sword & Sorcery", "sword-and-sorcery"),
                                GenreSeed("Romantasy", "romantasy"),
                                GenreSeed("Grimdark", "grimdark"),
                                GenreSeed("Portal Fantasy", "portal-fantasy"),
                                GenreSeed("Fairy Tale Retelling", "fairy-tale-retelling"),
                            ),
                    ),
                    GenreSeed(
                        name = "Science Fiction",
                        slug = "science-fiction",
                        children =
                            listOf(
                                GenreSeed("Hard Sci-Fi", "hard-sci-fi"),
                                GenreSeed("Space Opera", "space-opera"),
                                GenreSeed("Cyberpunk", "cyberpunk"),
                                GenreSeed("Post-Apocalyptic", "post-apocalyptic"),
                                GenreSeed("Military Sci-Fi", "military-sci-fi"),
                                GenreSeed("First Contact", "first-contact"),
                                GenreSeed("Time Travel", "time-travel"),
                                GenreSeed("Dystopian", "dystopian"),
                            ),
                    ),
                    GenreSeed(
                        name = "Mystery & Thriller",
                        slug = "mystery-thriller",
                        children =
                            listOf(
                                GenreSeed("Cozy Mystery", "cozy-mystery"),
                                GenreSeed("Police Procedural", "police-procedural"),
                                GenreSeed("Legal Thriller", "legal-thriller"),
                                GenreSeed("Psychological Thriller", "psychological-thriller"),
                                GenreSeed("Espionage", "espionage"),
                                GenreSeed("Crime Fiction", "crime-fiction"),
                                GenreSeed("Noir", "noir"),
                            ),
                    ),
                    GenreSeed(
                        name = "Romance",
                        slug = "romance",
                        children =
                            listOf(
                                GenreSeed("Contemporary Romance", "contemporary-romance"),
                                GenreSeed("Historical Romance", "historical-romance"),
                                GenreSeed("Paranormal Romance", "paranormal-romance"),
                                GenreSeed("Romantic Suspense", "romantic-suspense"),
                                GenreSeed("Romantic Comedy", "romantic-comedy"),
                            ),
                    ),
                    GenreSeed(
                        name = "Horror",
                        slug = "horror",
                        children =
                            listOf(
                                GenreSeed("Supernatural Horror", "supernatural-horror"),
                                GenreSeed("Cosmic Horror", "cosmic-horror"),
                                GenreSeed("Gothic Horror", "gothic-horror"),
                                GenreSeed("Slasher", "slasher"),
                            ),
                    ),
                    GenreSeed("Literary Fiction", "literary-fiction"),
                    GenreSeed(
                        name = "Historical Fiction",
                        slug = "historical-fiction",
                        children =
                            listOf(
                                GenreSeed("Ancient History", "ancient-history-fiction"),
                                GenreSeed("Medieval", "medieval-fiction"),
                                GenreSeed("Victorian", "victorian-fiction"),
                                GenreSeed("World War", "world-war-fiction"),
                            ),
                    ),
                    GenreSeed("Adventure", "adventure"),
                    GenreSeed("Humor", "humor"),
                    GenreSeed("Western", "western"),
                ),
        ),
        GenreSeed(
            name = "Non-Fiction",
            slug = "non-fiction",
            children =
                listOf(
                    GenreSeed(
                        name = "Biography & Memoir",
                        slug = "biography-memoir",
                        children =
                            listOf(
                                GenreSeed("Autobiography", "autobiography"),
                                GenreSeed("Biography", "biography"),
                                GenreSeed("Memoir", "memoir"),
                            ),
                    ),
                    GenreSeed(
                        name = "Self-Help & Personal Development",
                        slug = "self-help",
                        children =
                            listOf(
                                GenreSeed("Productivity", "productivity"),
                                GenreSeed("Relationships", "relationships"),
                                GenreSeed("Mental Health", "mental-health"),
                                GenreSeed("Mindfulness", "mindfulness"),
                            ),
                    ),
                    GenreSeed(
                        name = "Business & Finance",
                        slug = "business-finance",
                        children =
                            listOf(
                                GenreSeed("Entrepreneurship", "entrepreneurship"),
                                GenreSeed("Investing", "investing"),
                                GenreSeed("Leadership", "leadership"),
                                GenreSeed("Marketing", "marketing"),
                            ),
                    ),
                    GenreSeed(
                        name = "History",
                        slug = "history",
                        children =
                            listOf(
                                GenreSeed("Ancient History", "ancient-history"),
                                GenreSeed("Modern History", "modern-history"),
                                GenreSeed("Military History", "military-history"),
                            ),
                    ),
                    GenreSeed(
                        name = "Science & Nature",
                        slug = "science-nature",
                        children =
                            listOf(
                                GenreSeed("Physics", "physics"),
                                GenreSeed("Biology", "biology"),
                                GenreSeed("Astronomy", "astronomy"),
                                GenreSeed("Environment", "environment"),
                            ),
                    ),
                    GenreSeed("True Crime", "true-crime"),
                    GenreSeed("Religion & Spirituality", "religion-spirituality"),
                    GenreSeed("Philosophy", "philosophy"),
                    GenreSeed("Health & Fitness", "health-fitness"),
                    GenreSeed("Travel", "travel"),
                    GenreSeed("Cooking & Food", "cooking-food"),
                    GenreSeed("Politics & Social Sciences", "politics-social"),
                    GenreSeed("Technology", "technology"),
                ),
        ),
        GenreSeed(
            name = "Children's & Young Adult",
            slug = "children-young-adult",
            children =
                listOf(
                    GenreSeed("Picture Books", "picture-books"),
                    GenreSeed("Middle Grade", "middle-grade"),
                    GenreSeed(
                        name = "Young Adult",
                        slug = "young-adult",
                        children =
                            listOf(
                                GenreSeed("YA Fantasy", "ya-fantasy"),
                                GenreSeed("YA Sci-Fi", "ya-sci-fi"),
                                GenreSeed("YA Romance", "ya-romance"),
                                GenreSeed("YA Contemporary", "ya-contemporary"),
                            ),
                    ),
                ),
        ),
    )

/**
 * Seeds the default Genre taxonomy on a fresh installation. Writes through
 * [GenreRepository.upsert] — the real domain write path — so every seeded row is
 * indistinguishable from a curator creation. Idempotent: when any live genre
 * already exists, [isAlreadySeeded] returns `true` and [seed] is skipped, so a
 * second `SeedRunner.run()` is a no-op.
 *
 * Runs at order 40 — after [TagDomainSeeder] (order 30). Order is structural
 * rather than functional: genres don't depend on tags, but later domains that
 * cross-reference both want a stable run sequence.
 */
internal class GenreDomainSeeder(
    private val genreRepository: GenreRepository,
    private val clock: Clock = Clock.System,
) : DomainSeeder {
    override val domainName: String = "genres"

    override val order: Int = 40

    /** Returns true when at least one non-deleted genre row already exists. */
    override suspend fun isAlreadySeeded(): Boolean = genreRepository.count() > 0L

    override suspend fun seed() {
        val now = clock.now().toEpochMilliseconds()
        for (root in DEFAULT_GENRES) {
            seedRecursive(root, parentId = null, parentPath = "", depth = 0, now = now)
        }
    }

    private suspend fun seedRecursive(
        seed: GenreSeed,
        parentId: String?,
        parentPath: String,
        depth: Int,
        now: Long,
    ) {
        val path = "$parentPath/${seed.slug}"

        // Skip pre-existing slugs — a previous seed() that crashed mid-tree, or a manual create
        // before the seeder ran. The substrate upsert would otherwise hit the unique-slug index.
        val existing = genreRepository.findBySlug(seed.slug)
        val id =
            if (existing != null) {
                logger.info { "seed [$domainName]: genre '${seed.slug}' already present — skipping insert" }
                existing.id
            } else {
                val newId = Uuid.random().toString()
                val payload =
                    GenreSyncPayload(
                        id = newId,
                        name = seed.name,
                        slug = seed.slug,
                        path = path,
                        parentId = parentId,
                        depth = depth,
                        sortOrder = 0,
                        color = null,
                        description = null,
                        revision = 0L,
                        updatedAt = now,
                        createdAt = now,
                        deletedAt = null,
                    )
                when (val result = genreRepository.upsert(payload)) {
                    is AppResult.Success -> {
                        logger.info { "seed [$domainName]: genre '${seed.name}' created id=$newId path=$path" }
                        newId
                    }

                    is AppResult.Failure -> {
                        logger.warn {
                            "seed [$domainName]: genre '${seed.name}' not created — ${result.error.code}"
                        }
                        return
                    }
                }
            }

        for (child in seed.children) {
            seedRecursive(child, parentId = id, parentPath = path, depth = depth + 1, now = now)
        }
    }
}
