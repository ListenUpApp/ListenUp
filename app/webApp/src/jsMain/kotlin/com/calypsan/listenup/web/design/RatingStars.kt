package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.domain.RatingKeyStep
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import kotlin.math.ceil

/**
 * Five stars filled in halves from [halfStars] (2..10; 0 draws five empty stars).
 *
 * Read-only when [onHalfStarsChange] is null — small, for reader lines — and announced as one
 * image ("3.5 out of 5 stars"). With a callback it is an input: a focusable `role="slider"` that a
 * click sets by where it lands (the left half of a star counts half of it), and that the keyboard
 * steps one half at a time — the arrows, with Home and End jumping to one and five stars.
 *
 * Every star count it shows or speaks goes through [ListenerRatingLimits.starsLabel], so the
 * browser says exactly what Android and iOS say — and never "3.0", which is what a JS double
 * would print.
 */
@Composable
fun RatingStars(
    halfStars: Int,
    onHalfStarsChange: ((Int) -> Unit)? = null,
) {
    val spoken = "${ListenerRatingLimits.starsLabel(halfStars.toDouble())} out of $STAR_COUNT stars"
    Span(attrs = {
        classes("rs")
        if (onHalfStarsChange == null) {
            attr("role", "img")
            attr("aria-label", spoken)
        } else {
            classes("rs-input")
            attr("role", "slider")
            // A static name: the slider role says it is adjustable, and the value says the rest.
            attr("aria-label", "Rating")
            attr("aria-valuemin", "1")
            attr("aria-valuemax", STAR_COUNT.toString())
            val isRated = halfStars >= ListenerRatingLimits.MIN_HALF_STARS
            // Absent while nothing is chosen, so the number never disagrees with "Not rated".
            if (isRated) attr("aria-valuenow", ListenerRatingLimits.starsLabel(halfStars.toDouble()))
            attr("aria-valuetext", if (isRated) spoken else "Not rated")
            tabIndex(0)
            onKeyDown { event ->
                val next = halfStarsForKey(event.key, halfStars) ?: return@onKeyDown
                event.preventDefault()
                if (next != halfStars) onHalfStarsChange(next)
            }
            onClick { event ->
                val element = event.nativeEvent.currentTarget as? HTMLElement ?: return@onClick
                val rect = element.getBoundingClientRect()
                if (rect.width <= 0.0) return@onClick
                val picked = halfStarsAt(event.clientX - rect.left, rect.width)
                if (picked != halfStars) onHalfStarsChange(picked)
            }
        }
    }) {
        repeat(STAR_COUNT) { index ->
            Span(attrs = {
                classes("rs-s", starClass(halfStars, index))
                attr("aria-hidden", "true")
            }) { Text(STAR_GLYPH) }
        }
    }
}

/**
 * Where a key moves a [current] rating, or null when the key is not one the slider answers. The
 * stepping itself is [RatingKeyStep] — the same arithmetic Android's stars use.
 */
internal fun halfStarsForKey(
    key: String,
    current: Int,
): Int? =
    when (key) {
        "ArrowRight", "ArrowUp" -> RatingKeyStep.Increase
        "ArrowLeft", "ArrowDown" -> RatingKeyStep.Decrease
        "Home" -> RatingKeyStep.Lowest
        "End" -> RatingKeyStep.Highest
        else -> null
    }?.applyTo(current)

/**
 * The half-star rating a click [x] pixels from the start of five equal stars spanning [width] sets:
 * the left half of a star counts half of it, the right half counts it whole. Clamped to one..five.
 */
internal fun halfStarsAt(
    x: Double,
    width: Double,
): Int {
    val starWidth = width / STAR_COUNT
    return ceil(x / starWidth * 2)
        .toInt()
        .coerceIn(ListenerRatingLimits.MIN_HALF_STARS, ListenerRatingLimits.MAX_HALF_STARS)
}

/** Whether star [index] (0-based) of a [halfStars] rating is full, half, or empty. */
private fun starClass(
    halfStars: Int,
    index: Int,
): String {
    val starEnd = (index + 1) * 2
    return when {
        halfStars >= starEnd -> "is-full"
        halfStars == starEnd - 1 -> "is-half"
        else -> "is-empty"
    }
}

private const val STAR_COUNT = 5

private const val STAR_GLYPH = "★"
