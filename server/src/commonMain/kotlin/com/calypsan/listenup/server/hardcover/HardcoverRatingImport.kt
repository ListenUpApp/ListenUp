package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.domain.ListenerRatingLimits
import kotlin.math.roundToInt

/**
 * Hardcover's `user_books.rating` (0.5 steps; null or 0 when unrated) as ListenUp half stars
 * (2..10), or null for no rating. Hardcover's half-star rating is one star here: ListenUp's floor.
 */
internal fun hardcoverHalfStars(rating: Double?): Int? =
    rating
        ?.takeIf { it > 0.0 }
        ?.let { (it * 2).roundToInt().coerceIn(ListenerRatingLimits.MIN_HALF_STARS, ListenerRatingLimits.MAX_HALF_STARS) }
