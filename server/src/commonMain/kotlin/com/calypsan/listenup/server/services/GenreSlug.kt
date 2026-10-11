package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.SlugOutcome
import com.calypsan.listenup.server.sync.slugify

/**
 * Normalizes raw genre display names into URL-safe slugs; the algorithm is documented on
 * [com.calypsan.listenup.server.sync.slugify]. It is the same one tags use, so raw alias strings map
 * to the same canonical slugs (`"sword-and-sorcery"`) at scanner time.
 */
object GenreSlug {
    private const val MAX_SLUG_LENGTH = 100

    /**
     * Normalize a genre display name to its canonical slug, or fail with [GenreError.InvalidInput]
     * when nothing sluggable is left or the slug exceeds [MAX_SLUG_LENGTH] characters.
     */
    fun normalize(rawName: String): AppResult<String> =
        when (val outcome = slugify(rawName, MAX_SLUG_LENGTH)) {
            is SlugOutcome.Ok -> {
                AppResult.Success(outcome.slug)
            }

            SlugOutcome.Empty -> {
                AppResult.Failure(GenreError.InvalidInput(debugInfo = "normalized to empty"))
            }

            SlugOutcome.TooLong -> {
                AppResult.Failure(GenreError.InvalidInput(debugInfo = "exceeds $MAX_SLUG_LENGTH chars"))
            }
        }
}
