package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult

/** Normalizes raw mood display names into URL-safe slugs; the algorithm is documented on [slugify]. */
object MoodSlug {
    private const val MAX_SLUG_LENGTH = 64

    /**
     * Normalize a mood display name to its canonical slug, or fail with [MoodError.InvalidName] when
     * nothing sluggable is left and [MoodError.NameTooLong] past [MAX_SLUG_LENGTH] characters.
     */
    fun normalize(rawName: String): AppResult<String> =
        when (val outcome = slugify(rawName, MAX_SLUG_LENGTH)) {
            is SlugOutcome.Ok -> AppResult.Success(outcome.slug)
            SlugOutcome.Empty -> AppResult.Failure(MoodError.InvalidName())
            SlugOutcome.TooLong -> AppResult.Failure(MoodError.NameTooLong())
        }
}
