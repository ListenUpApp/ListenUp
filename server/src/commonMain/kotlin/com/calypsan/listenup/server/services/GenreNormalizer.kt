package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult

/**
 * Built-in genre normalization.
 *
 * Resolves a raw scanner genre string to canonical taxonomy slug(s) via a baked-in
 * alias map (Audible's category taxonomy + common variations). This is the AUTO path;
 * the curator `genre_aliases` DB table (custom mappings) and `pending_book_genres`
 * (unresolved) remain the higher-precedence and fallback paths respectively, in
 * [BookRepository.processGenreStrings].
 *
 * Keys and lookups both go through [GenreSlug] so the map aligns with the seeded
 * taxonomy's canonical slugs (see [GenreSlug]'s KDoc on the deliberate `& -> and` rule).
 */
object GenreNormalizer {
    /**
     * Normalize a raw genre string to canonical slug(s).
     * - blank / symbol-only / over-long -> [emptyList].
     * - a known alias -> its mapped canonical slug(s).
     * - otherwise -> the bare slug (matched against the live taxonomy upstream).
     */
    fun normalizeToSlugs(raw: String): List<String> {
        val slug = slugify(raw) ?: return emptyList()
        return CANONICAL_ALIASES[slug] ?: listOf(slug)
    }

    /** Distinct canonical slugs this map can produce — used by the taxonomy-alignment test. */
    internal fun canonicalSlugs(): Set<String> = CANONICAL_ALIASES.values.flatten().toSet()

    private fun slugify(raw: String): String? =
        when (val r = GenreSlug.normalize(raw)) {
            is AppResult.Success -> r.data
            is AppResult.Failure -> null
        }

    /**
     * Canonical taxonomy slugs the alias map resolves to — the single source of truth for the
     * right-hand side of [RAW_ALIASES]. Named constants keep the taxonomy typo-proof and let the
     * map read as "raw key -> canonical slug" instead of a wall of repeated string literals.
     */
    private object Slug {
        const val ADVENTURE = "adventure"
        const val BIOGRAPHY_MEMOIR = "biography-memoir"
        const val BUSINESS_FINANCE = "business-finance"
        const val CHILDREN_YOUNG_ADULT = "children-young-adult"
        const val CONTEMPORARY_ROMANCE = "contemporary-romance"
        const val COZY_MYSTERY = "cozy-mystery"
        const val CRIME_FICTION = "crime-fiction"
        const val EPIC_FANTASY = "epic-fantasy"
        const val FANTASY = "fantasy"
        const val FICTION = "fiction"
        const val HEALTH_FITNESS = "health-fitness"
        const val HISTORICAL_FICTION = "historical-fiction"
        const val HISTORY = "history"
        const val HORROR = "horror"
        const val HUMOR = "humor"
        const val LITERARY_FICTION = "literary-fiction"
        const val LITRPG = "litrpg"
        const val MYSTERY_THRILLER = "mystery-thriller"
        const val NON_FICTION = "non-fiction"
        const val PARANORMAL_ROMANCE = "paranormal-romance"
        const val POLITICS_SOCIAL = "politics-social"
        const val PROGRESSION_FANTASY = "progression-fantasy"
        const val RELIGION_SPIRITUALITY = "religion-spirituality"
        const val ROMANCE = "romance"
        const val ROMANTASY = "romantasy"
        const val SCIENCE_FICTION = "science-fiction"
        const val SCIENCE_NATURE = "science-nature"
        const val SELF_HELP = "self-help"
        const val SWORD_AND_SORCERY = "sword-and-sorcery"
        const val TECHNOLOGY = "technology"
        const val TRAVEL = "travel"
        const val YOUNG_ADULT = "young-adult"
    }

    /** Go `CanonicalAliases`, transcribed verbatim from `internal/genre/aliases.go`. Keys re-normalized via [GenreSlug] at load. */
    private val RAW_ALIASES: Map<String, List<String>> =
        mapOf(
            // ===========================================
            // AUDIBLE TOP-LEVEL CATEGORIES (24 categories)
            // ===========================================
            // Arts & Entertainment
            "arts-entertainment" to listOf(Slug.FICTION),
            "arts-and-entertainment" to listOf(Slug.FICTION),
            "arts & entertainment" to listOf(Slug.FICTION),
            // Biographies & Memoirs
            "biographies-memoirs" to listOf(Slug.BIOGRAPHY_MEMOIR),
            "biographies-and-memoirs" to listOf(Slug.BIOGRAPHY_MEMOIR),
            "biographies & memoirs" to listOf(Slug.BIOGRAPHY_MEMOIR),
            "biography-memoirs" to listOf(Slug.BIOGRAPHY_MEMOIR),
            "biography & memoir" to listOf(Slug.BIOGRAPHY_MEMOIR),
            // Business & Careers
            "business-careers" to listOf(Slug.BUSINESS_FINANCE),
            "business-and-careers" to listOf(Slug.BUSINESS_FINANCE),
            "business & careers" to listOf(Slug.BUSINESS_FINANCE),
            // Children's Audiobooks
            "children-s-audiobooks" to listOf(Slug.CHILDREN_YOUNG_ADULT),
            "childrens-audiobooks" to listOf(Slug.CHILDREN_YOUNG_ADULT),
            "children-s-books" to listOf(Slug.CHILDREN_YOUNG_ADULT),
            "childrens-books" to listOf(Slug.CHILDREN_YOUNG_ADULT),
            "children" to listOf(Slug.CHILDREN_YOUNG_ADULT),
            // Comedy & Humor
            "comedy-humor" to listOf(Slug.HUMOR),
            "comedy-and-humor" to listOf(Slug.HUMOR),
            "comedy & humor" to listOf(Slug.HUMOR),
            "comedy" to listOf(Slug.HUMOR),
            // Computers & Technology
            "computers-technology" to listOf(Slug.TECHNOLOGY),
            "computers-and-technology" to listOf(Slug.TECHNOLOGY),
            "computers & technology" to listOf(Slug.TECHNOLOGY),
            // Education & Learning
            "education-learning" to listOf(Slug.NON_FICTION),
            "education-and-learning" to listOf(Slug.NON_FICTION),
            "education & learning" to listOf(Slug.NON_FICTION),
            "education" to listOf(Slug.NON_FICTION),
            // Erotica (& Sexuality)
            "erotica-sexuality" to listOf(Slug.ROMANCE),
            "erotica" to listOf(Slug.ROMANCE),
            // Health & Wellness
            "health-wellness" to listOf(Slug.HEALTH_FITNESS),
            "health-and-wellness" to listOf(Slug.HEALTH_FITNESS),
            "health & wellness" to listOf(Slug.HEALTH_FITNESS),
            // History (top-level)
            "history" to listOf(Slug.HISTORY),
            // Home & Garden
            "home-garden" to listOf(Slug.NON_FICTION),
            "home-and-garden" to listOf(Slug.NON_FICTION),
            "home & garden" to listOf(Slug.NON_FICTION),
            // LGBTQ+ Audiobooks
            "lgbtq-audiobooks" to listOf(Slug.FICTION),
            "lgbtq" to listOf(Slug.FICTION),
            "lgbt" to listOf(Slug.FICTION),
            // Literature & Fiction
            "literature-fiction" to listOf(Slug.FICTION),
            "literature-and-fiction" to listOf(Slug.FICTION),
            "literature & fiction" to listOf(Slug.FICTION),
            "literature" to listOf(Slug.FICTION),
            // Money & Finance
            "money-finance" to listOf(Slug.BUSINESS_FINANCE),
            "money-and-finance" to listOf(Slug.BUSINESS_FINANCE),
            "money & finance" to listOf(Slug.BUSINESS_FINANCE),
            // Mystery, Thriller & Suspense
            "mystery-thriller-suspense" to listOf(Slug.MYSTERY_THRILLER),
            "mystery-thriller-and-suspense" to listOf(Slug.MYSTERY_THRILLER),
            "mystery-thriller & suspense" to listOf(Slug.MYSTERY_THRILLER),
            "mystery, thriller & suspense" to listOf(Slug.MYSTERY_THRILLER),
            "mystery, thriller and suspense" to listOf(Slug.MYSTERY_THRILLER),
            // Politics & Social Sciences
            "politics-social-sciences" to listOf(Slug.POLITICS_SOCIAL),
            "politics-and-social-sciences" to listOf(Slug.POLITICS_SOCIAL),
            "politics & social sciences" to listOf(Slug.POLITICS_SOCIAL),
            // Relationships, Parenting & Personal Development
            "relationships-parenting-personal-development" to listOf(Slug.SELF_HELP),
            "relationships-parenting-and-personal-development" to listOf(Slug.SELF_HELP),
            "parenting-families" to listOf(Slug.SELF_HELP),
            "parenting-and-families" to listOf(Slug.SELF_HELP),
            "parenting & families" to listOf(Slug.SELF_HELP),
            "personal-development" to listOf(Slug.SELF_HELP),
            // Religion & Spirituality
            "religion-spirituality" to listOf(Slug.RELIGION_SPIRITUALITY),
            "religion-and-spirituality" to listOf(Slug.RELIGION_SPIRITUALITY),
            "religion & spirituality" to listOf(Slug.RELIGION_SPIRITUALITY),
            // Romance
            "romance" to listOf(Slug.ROMANCE),
            // Science & Engineering
            "science-engineering" to listOf(Slug.SCIENCE_NATURE),
            "science-and-engineering" to listOf(Slug.SCIENCE_NATURE),
            "science & engineering" to listOf(Slug.SCIENCE_NATURE),
            // Science Fiction & Fantasy
            "science-fiction-fantasy" to listOf(Slug.SCIENCE_FICTION, Slug.FANTASY),
            "science-fiction-and-fantasy" to listOf(Slug.SCIENCE_FICTION, Slug.FANTASY),
            "science fiction & fantasy" to listOf(Slug.SCIENCE_FICTION, Slug.FANTASY),
            "sci-fi-fantasy" to listOf(Slug.SCIENCE_FICTION, Slug.FANTASY),
            "sci-fi & fantasy" to listOf(Slug.SCIENCE_FICTION, Slug.FANTASY),
            // Sports & Outdoors
            "sports-outdoors" to listOf(Slug.NON_FICTION),
            "sports-and-outdoors" to listOf(Slug.NON_FICTION),
            "sports & outdoors" to listOf(Slug.NON_FICTION),
            // Teen & Young Adult
            "teens-young-adult" to listOf(Slug.YOUNG_ADULT),
            "teen-young-adult" to listOf(Slug.YOUNG_ADULT),
            "teen-and-young-adult" to listOf(Slug.YOUNG_ADULT),
            "teen & young adult" to listOf(Slug.YOUNG_ADULT),
            // Travel & Tourism
            "travel-tourism" to listOf(Slug.TRAVEL),
            "travel-and-tourism" to listOf(Slug.TRAVEL),
            "travel & tourism" to listOf(Slug.TRAVEL),
            // ===========================================
            // LITERATURE & FICTION SUBCATEGORIES
            // ===========================================
            // Action & Adventure
            "action-adventure" to listOf(Slug.ADVENTURE),
            "action-and-adventure" to listOf(Slug.ADVENTURE),
            "action & adventure" to listOf(Slug.ADVENTURE),
            // African American
            "african-american" to listOf(Slug.FICTION),
            "african-american-fiction" to listOf(Slug.FICTION),
            // Ancient, Classical & Medieval Literature
            "ancient-classical-medieval-literature" to listOf(Slug.LITERARY_FICTION),
            "classical-literature" to listOf(Slug.LITERARY_FICTION),
            "medieval-literature" to listOf(Slug.LITERARY_FICTION),
            // Anthologies & Short Stories
            "anthologies-short-stories" to listOf(Slug.FICTION),
            "anthologies-and-short-stories" to listOf(Slug.FICTION),
            "anthologies & short stories" to listOf(Slug.FICTION),
            "short-stories" to listOf(Slug.FICTION),
            "anthologies" to listOf(Slug.FICTION),
            // Classics
            "classics" to listOf(Slug.LITERARY_FICTION),
            "classic" to listOf(Slug.LITERARY_FICTION),
            "classic-fiction" to listOf(Slug.LITERARY_FICTION),
            // Drama & Plays
            "drama-plays" to listOf(Slug.FICTION),
            "drama-and-plays" to listOf(Slug.FICTION),
            "drama & plays" to listOf(Slug.FICTION),
            "drama" to listOf(Slug.FICTION),
            "plays" to listOf(Slug.FICTION),
            // Essays
            "essays" to listOf(Slug.NON_FICTION),
            // Genre Fiction
            "genre-fiction" to listOf(Slug.FICTION),
            "general-fiction" to listOf(Slug.FICTION),
            // Historical Fiction
            "historical" to listOf(Slug.HISTORICAL_FICTION),
            "historical-fiction" to listOf(Slug.HISTORICAL_FICTION),
            "historical fiction" to listOf(Slug.HISTORICAL_FICTION),
            // Horror
            "horror" to listOf(Slug.HORROR),
            "scary" to listOf(Slug.HORROR),
            // Humor & Satire
            "humor-satire" to listOf(Slug.HUMOR),
            "humor-and-satire" to listOf(Slug.HUMOR),
            "humor & satire" to listOf(Slug.HUMOR),
            "satire" to listOf(Slug.HUMOR),
            // Literary History & Criticism
            "literary-history-criticism" to listOf(Slug.NON_FICTION),
            "literary-history-and-criticism" to listOf(Slug.NON_FICTION),
            "literary-criticism" to listOf(Slug.NON_FICTION),
            // Poetry
            "poetry" to listOf(Slug.FICTION),
            // Women's Fiction
            "womens-fiction" to listOf(Slug.FICTION),
            "women-s-fiction" to listOf(Slug.FICTION),
            // World Literature
            "world-literature" to listOf(Slug.LITERARY_FICTION),
            // ===========================================
            // SCIENCE FICTION & FANTASY SUBCATEGORIES
            // ===========================================
            // Science Fiction variations
            "sci-fi" to listOf(Slug.SCIENCE_FICTION),
            "scifi" to listOf(Slug.SCIENCE_FICTION),
            "sf" to listOf(Slug.SCIENCE_FICTION),
            "science fiction" to listOf(Slug.SCIENCE_FICTION),
            // Fantasy variations
            "high fantasy" to listOf(Slug.EPIC_FANTASY),
            "sword and sorcery" to listOf(Slug.SWORD_AND_SORCERY),
            "s&s" to listOf(Slug.SWORD_AND_SORCERY),
            // Combined
            "fantasy-romance" to listOf(Slug.FANTASY, Slug.ROMANCE),
            "romantic-fantasy" to listOf(Slug.ROMANTASY),
            // ===========================================
            // MYSTERY, THRILLER & SUSPENSE SUBCATEGORIES
            // ===========================================
            "thriller" to listOf(Slug.MYSTERY_THRILLER),
            "suspense" to listOf(Slug.MYSTERY_THRILLER),
            "thriller-suspense" to listOf(Slug.MYSTERY_THRILLER),
            "thriller-and-suspense" to listOf(Slug.MYSTERY_THRILLER),
            "thriller & suspense" to listOf(Slug.MYSTERY_THRILLER),
            "suspense-thriller" to listOf(Slug.MYSTERY_THRILLER),
            "mystery-suspense" to listOf(Slug.MYSTERY_THRILLER),
            "mysteries" to listOf(Slug.MYSTERY_THRILLER),
            "thrillers" to listOf(Slug.MYSTERY_THRILLER),
            "mystery-thriller" to listOf(Slug.MYSTERY_THRILLER),
            "mystery" to listOf(Slug.MYSTERY_THRILLER),
            "crime" to listOf(Slug.CRIME_FICTION),
            "crime-fiction" to listOf(Slug.CRIME_FICTION),
            "crime fiction" to listOf(Slug.CRIME_FICTION),
            "detective" to listOf(Slug.MYSTERY_THRILLER),
            "traditional-detectives" to listOf(Slug.MYSTERY_THRILLER),
            "traditional detectives" to listOf(Slug.MYSTERY_THRILLER),
            "cozy" to listOf(Slug.COZY_MYSTERY),
            "cozy-mystery" to listOf(Slug.COZY_MYSTERY),
            "whodunit" to listOf(Slug.MYSTERY_THRILLER),
            // ===========================================
            // YOUNG ADULT VARIATIONS
            // ===========================================
            "ya" to listOf(Slug.YOUNG_ADULT),
            "young adult" to listOf(Slug.YOUNG_ADULT),
            "teen" to listOf(Slug.YOUNG_ADULT),
            // ===========================================
            // NON-FICTION VARIATIONS
            // ===========================================
            "self-help" to listOf(Slug.SELF_HELP),
            "selfhelp" to listOf(Slug.SELF_HELP),
            "self help" to listOf(Slug.SELF_HELP),
            // ===========================================
            // LITRPG / PROGRESSION FANTASY
            // ===========================================
            "litrpg" to listOf(Slug.LITRPG),
            "lit-rpg" to listOf(Slug.LITRPG),
            "lit rpg" to listOf(Slug.LITRPG),
            "gamelit" to listOf(Slug.LITRPG),
            "progression" to listOf(Slug.PROGRESSION_FANTASY),
            "progression-fantasy" to listOf(Slug.PROGRESSION_FANTASY),
            "cultivation" to listOf(Slug.PROGRESSION_FANTASY),
            // ===========================================
            // ROMANCE SUBCATEGORIES
            // ===========================================
            "contemporary-romance" to listOf(Slug.CONTEMPORARY_ROMANCE),
            "modern-romance" to listOf(Slug.CONTEMPORARY_ROMANCE),
            "paranormal-romance" to listOf(Slug.PARANORMAL_ROMANCE),
            "pnr" to listOf(Slug.PARANORMAL_ROMANCE),
            // ===========================================
            // ANIMAL/CHILDREN'S FICTION
            // ===========================================
            "animal-fiction" to listOf(Slug.FICTION),
            "animal fiction" to listOf(Slug.FICTION),
            "animals" to listOf(Slug.FICTION),
            // ===========================================
            // BASE CATEGORIES (identity mappings for validation)
            // ===========================================
            "fiction" to listOf(Slug.FICTION),
            "non-fiction" to listOf(Slug.NON_FICTION),
            "nonfiction" to listOf(Slug.NON_FICTION),
        )

    private val CANONICAL_ALIASES: Map<String, List<String>> =
        buildMap {
            RAW_ALIASES.forEach { (rawKey, canonical) ->
                slugify(rawKey)?.let { put(it, canonical) }
            }
        }
}
