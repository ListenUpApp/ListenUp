package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult

/**
 * Normalizes raw mood display names into URL-safe slugs.
 *
 * Algorithm (in order):
 * 1. Strip diacritics via [foldDiacritics] (the one platform Unicode step: NFKD on JVM,
 *    a Latin folding table on native).
 * 2. Lowercase.
 * 3. Replace `&` with `" and "` (with surrounding spaces so adjacent words don't run together).
 * 4. Replace runs of non-alphanumeric characters with a single `-`.
 * 5. Trim leading/trailing `-`.
 * 6. Empty result → [MoodError.InvalidName].
 * 7. Length > 64 → [MoodError.NameTooLong].
 */
object MoodSlug {
    private const val MAX_SLUG_LENGTH = 64

    /**
     * Normalize a mood display name to its canonical slug.
     *
     * Returns [AppResult.Failure] with [MoodError.InvalidName] when [rawName] is blank
     * or produces an empty slug after normalization (e.g. only special characters).
     * Returns [MoodError.NameTooLong] when [rawName] exceeds [MAX_SLUG_LENGTH] characters
     * after normalization.
     */
    fun normalize(rawName: String): AppResult<String> {
        // Step 1: strip diacritics — the one step needing a platform Unicode primitive.
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
            return AppResult.Failure(MoodError.InvalidName())
        }

        // Step 7: length guard.
        if (slug.length > MAX_SLUG_LENGTH) {
            return AppResult.Failure(MoodError.NameTooLong())
        }

        return AppResult.Success(slug)
    }
}
