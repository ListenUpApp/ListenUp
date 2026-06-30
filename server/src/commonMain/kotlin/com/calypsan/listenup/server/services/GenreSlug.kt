package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.foldDiacritics

/**
 * Normalizes raw genre display names into URL-safe slugs.
 *
 * Algorithm (in order):
 * 1. Strip diacritics via the [foldDiacritics] platform seam (JVM NFKD
 *    decomposition / native Latin folding table), so accented Latin letters
 *    reduce to their base ASCII letter.
 * 2. Lowercase.
 * 3. Replace `&` with `" and "` (with surrounding spaces so adjacent words don't
 *    run together).
 * 4. Replace runs of non-alphanumeric characters with a single `-`.
 * 5. Trim leading/trailing `-`.
 * 6. Empty result → [GenreError.InvalidInput].
 * 7. Length > 100 → [GenreError.InvalidInput].
 *
 * Mirrors [com.calypsan.listenup.server.sync.TagSlug]. The `& → and` substitution
 * is what produces canonical slugs such as `"sword-and-sorcery"`; adopting
 * TagSlug's rule keeps raw alias strings mapping to the same canonical slugs at
 * scanner time.
 */
object GenreSlug {
    private const val MAX_SLUG_LENGTH = 100

    /**
     * Normalize a genre display name to its canonical slug.
     *
     * Returns [AppResult.Failure] with [GenreError.InvalidInput] when [rawName]
     * is blank, produces an empty slug after normalization (e.g. only special
     * characters), or exceeds [MAX_SLUG_LENGTH] characters after normalization.
     */
    fun normalize(rawName: String): AppResult<String> {
        // Step 1: strip diacritics via the platform seam (JVM NFKD / native folding table).
        val diacriticsStripped = foldDiacritics(rawName)

        // Step 2: lowercase.
        val lowered = diacriticsStripped.lowercase()

        // Step 3: & → " and " (spaces ensure clean word separation).
        val ampersandReplaced = lowered.replace("&", " and ")

        // Step 4: collapse runs of non-alphanumeric to a single dash.
        val dashed = ampersandReplaced.replace(Regex("[^a-z0-9]+"), "-")

        // Step 5: trim leading/trailing dashes.
        val slug = dashed.trim('-')

        // Step 6: empty after normalization.
        if (slug.isEmpty()) {
            return AppResult.Failure(GenreError.InvalidInput(debugInfo = "normalized to empty"))
        }

        // Step 7: length guard.
        if (slug.length > MAX_SLUG_LENGTH) {
            return AppResult.Failure(
                GenreError.InvalidInput(debugInfo = "exceeds $MAX_SLUG_LENGTH chars"),
            )
        }

        return AppResult.Success(slug)
    }
}
