package com.calypsan.listenup.domain

/**
 * A keyboard adjustment of a star-rating input, free of any platform's key codes: each platform
 * maps its own keys onto these (the arrows, Home and End) and every one of them then steps a
 * rating the same way through [applyTo].
 */
enum class RatingKeyStep {
    /** One half-star up — right or up. */
    Increase,

    /** One half-star down — left or down. */
    Decrease,

    /** One star — Home. */
    Lowest,

    /** Five stars — End. */
    Highest,
    ;

    /**
     * The rating this step moves [currentHalfStars] to, clamped to one..five stars — so the first
     * step on an unrated input (0) lands on one star rather than on a value Save would refuse.
     */
    fun applyTo(currentHalfStars: Int): Int =
        when (this) {
            Increase -> currentHalfStars + 1
            Decrease -> currentHalfStars - 1
            Lowest -> ListenerRatingLimits.MIN_HALF_STARS
            Highest -> ListenerRatingLimits.MAX_HALF_STARS
        }.coerceIn(ListenerRatingLimits.MIN_HALF_STARS, ListenerRatingLimits.MAX_HALF_STARS)
}
