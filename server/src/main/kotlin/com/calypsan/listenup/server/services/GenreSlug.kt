package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import java.text.Normalizer

/**
 * Normalizes raw genre display names into URL-safe slugs.
 *
 * Algorithm (in order):
 * 1. NFKD normalize → strip combining marks (diacritics). `java.text.Normalizer`
 *    is the only JVM API that does full Unicode decomposition; it is isolated
 *    here behind the public [normalize] surface.
 * 2. Lowercase.
 * 3. Replace `&` with `" and "` (with surrounding spaces so adjacent words don't
 *    run together).
 * 4. Replace runs of non-alphanumeric characters with a single `-`.
 * 5. Trim leading/trailing `-`.
 * 6. Empty result → [GenreError.InvalidInput].
 * 7. Length > 100 → [GenreError.InvalidInput].
 *
 * Mirrors [com.calypsan.listenup.server.sync.TagSlug]. Diverges from Go's
 * `internal/genre/slug.go`, which has no `& → and` substitution. The Go-era
 * `defaults.go` authors canonical slugs such as `"sword-and-sorcery"` that
 * only this pipeline reproduces — the Go `Slugify()` was inconsistent with
 * its own seeded taxonomy. The Kotlin port resolves the inconsistency by
 * adopting TagSlug's rule, so `aliases.go` raw strings map to the same
 * canonical slugs at scanner time.
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
