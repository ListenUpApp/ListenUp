package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.server.services.GenreNormalizer

/**
 * Classifies Audible product topic tags ([ProductTag]) into the two dimensions
 * ListenUp persists on a metadata match: **Moods** and **Tropes (Tags)**.
 *
 * Routing by [ProductTag.type]:
 *  - `mood`            → [Classified.moods]
 *  - `theme`           → [Classified.tags], excluding any theme already applied
 *                        to the book as a genre (see exclusion rule below)
 *  - everything else   (`genre`, `social_media`, `audible_editors`, …) → dropped
 *
 * **Genre exclusion is alias-aware and slug-based, never raw-name-based.** A
 * `theme` is dropped when its *canonical* genre slug is in `appliedGenreSlugs`.
 * Each theme name is run through [GenreNormalizer.normalizeToSlugs] — the same
 * pipeline [com.calypsan.listenup.server.services.BookGenreWriter] uses to map a
 * raw genre string onto the live taxonomy — so a theme "Sci-Fi" (which
 * canonicalizes to `science-fiction`) is excluded when the book carries the
 * applied genre "Science Fiction". A naive slugify (`"Sci-Fi"` → `"sci-fi"`)
 * would miss this renamed-genre case and leak the genre back in as a trope.
 */
object ProductTagClassifier {
    /** The result of classifying a book's product tags into moods and tropes. */
    data class Classified(
        val moods: List<String>,
        val tags: List<String>,
    )

    /**
     * Classifies [tags] into moods and tropes, excluding any `theme` whose
     * canonical genre slug(s) intersect [appliedGenreSlugs].
     */
    fun classify(
        tags: List<ProductTag>,
        appliedGenreSlugs: Set<String>,
    ): Classified {
        val moods = mutableListOf<String>()
        val tropes = mutableListOf<String>()

        for (tag in tags) {
            when (tag.type) {
                MOOD -> moods += tag.name
                THEME -> if (!isAppliedAsGenre(tag.name, appliedGenreSlugs)) tropes += tag.name
                // genre, social_media, audible_editors, and any future type → dropped.
            }
        }

        return Classified(moods = moods, tags = tropes)
    }

    /**
     * True when [themeName]'s canonical genre slug(s) overlap [appliedGenreSlugs].
     * One raw name can canonicalize to several slugs (e.g. "Sci-Fi & Fantasy"),
     * so any intersection counts as "already applied as a genre".
     */
    private fun isAppliedAsGenre(
        themeName: String,
        appliedGenreSlugs: Set<String>,
    ): Boolean = GenreNormalizer.normalizeToSlugs(themeName).any { it in appliedGenreSlugs }

    private const val MOOD = "mood"
    private const val THEME = "theme"
}
