package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.result.AppResult
import java.text.Normalizer

/**
 * Normalizes raw tag display names into URL-safe slugs.
 *
 * Algorithm (in order):
 * 1. NFKD normalize → strip combining marks (diacritics). `java.text.Normalizer`
 *    is the only JVM API that does full Unicode decomposition; it is isolated here
 *    behind the public [normalize] surface.
 * 2. Lowercase.
 * 3. Replace `&` with `" and "` (with surrounding spaces so adjacent words don't run together).
 * 4. Replace runs of non-alphanumeric characters with a single `-`.
 * 5. Trim leading/trailing `-`.
 * 6. Empty result → [TagError.InvalidName].
 * 7. Length > 64 → [TagError.NameTooLong].
 */
object TagSlug {
    private const val MAX_SLUG_LENGTH = 64

    /**
     * Normalize a tag display name to its canonical slug.
     *
     * Returns [AppResult.Failure] with [TagError.InvalidName] when [rawName] is blank
     * or produces an empty slug after normalization (e.g. only special characters).
     * Returns [TagError.NameTooLong] when [rawName] exceeds [MAX_SLUG_LENGTH] characters
     * after normalization.
     */
    fun normalize(rawName: String): AppResult<String> {
        // Step 1: NFKD decomposition + diacritic removal (JVM-only; isolated here).
        val decomposed = Normalizer.normalize(rawName, Normalizer.Form.NFKD)
        val diacriticsStripped = decomposed.replace(Regex("\\p{Mn}"), "")

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
            return AppResult.Failure(TagError.InvalidName())
        }

        // Step 7: length guard.
        if (slug.length > MAX_SLUG_LENGTH) {
            return AppResult.Failure(TagError.NameTooLong())
        }

        return AppResult.Success(slug)
    }
}
