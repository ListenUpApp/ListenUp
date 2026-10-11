package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.result.AppResult

/** Normalizes raw tag display names into URL-safe slugs; the algorithm is documented on [slugify]. */
object TagSlug {
    private const val MAX_SLUG_LENGTH = 64

    /**
     * Normalize a tag display name to its canonical slug, or fail with [TagError.InvalidName] when
     * nothing sluggable is left and [TagError.NameTooLong] past [MAX_SLUG_LENGTH] characters.
     */
    fun normalize(rawName: String): AppResult<String> =
        when (val outcome = slugify(rawName, MAX_SLUG_LENGTH)) {
            is SlugOutcome.Ok -> AppResult.Success(outcome.slug)
            SlugOutcome.Empty -> AppResult.Failure(TagError.InvalidName())
            SlugOutcome.TooLong -> AppResult.Failure(TagError.NameTooLong())
        }
}
