package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.domain.RatingKeyStep
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.ceil

/**
 * Five stars filled in halves from [halfStars] (2..10; 0 draws five empty stars).
 *
 * Read-only when [onHalfStarsChange] is null — small, for reader lines — and announced as one
 * image ("3.5 out of 5 stars"). With a callback it is an input: a focusable `role="slider"` named
 * [label], that a click sets by where it lands, measured from the start edge (the first half of a
 * star counts half of it; in a right-to-left page the stars run from the right); that a pointer
 * drags across, previewing each half; and that the keyboard steps one half at a time — the arrows,
 * with Home and End jumping to one and five stars. [onHalfStarsCommit] hears the value the listener
 * settled on: a click (which also ends every drag), or each key step. Without it nothing saves until
 * a button does (the rate dialog).
 *
 * Every star count it shows or speaks goes through [ListenerRatingLimits.starsLabel], so the
 * browser says exactly what Android and iOS say — and never "3.0", which is what a JS double
 * would print.
 */
@Composable
fun RatingStars(
    halfStars: Int,
    onHalfStarsChange: ((Int) -> Unit)? = null,
    onHalfStarsCommit: ((Int) -> Unit)? = null,
    label: String = "Rating",
) {
    val spoken = "${ListenerRatingLimits.starsLabel(halfStars.toDouble())} out of $STAR_COUNT stars"
    // The drag's listeners are attached once, so they read the latest value and callback through these.
    val latestHalfStars by rememberUpdatedState(halfStars)
    val latestOnChange by rememberUpdatedState(onHalfStarsChange)
    Span(attrs = {
        classes("rs")
        if (onHalfStarsChange == null) {
            attr("role", "img")
            attr("aria-label", spoken)
        } else {
            classes("rs-input")
            attr("role", "slider")
            // A static name: the slider role says it is adjustable, and the value says the rest.
            attr("aria-label", label)
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
                onHalfStarsCommit?.invoke(next)
            }
            ref { element -> dragGestures(element, { latestHalfStars }, { latestOnChange }) }
            onClick { event ->
                val element = event.nativeEvent.currentTarget as? HTMLElement ?: return@onClick
                val picked = halfStarsForClick(element, event.clientX.toDouble()) ?: return@onClick
                if (picked != halfStars) onHalfStarsChange(picked)
                onHalfStarsCommit?.invoke(picked)
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
 * A pointer pressed on the stars and moved across them previews each half it crosses, through
 * [onChange]; the `click` the browser fires when it lets go (on the element that captured it) is
 * what commits, so a tap and a drag share one commit path. A move with no press is ignored.
 */
private fun DisposableEffectScope.dragGestures(
    element: HTMLElement,
    halfStars: () -> Int,
    onChange: () -> ((Int) -> Unit)?,
): DisposableEffectResult {
    var pressed: Int? = null
    val onDown: (Event) -> Unit = { event ->
        val pointer = event as PointerEvent
        pressed = pointer.pointerId
        capturePointer(element, pointer.pointerId)
    }
    val onMove: (Event) -> Unit = { event ->
        val pointer = event as PointerEvent
        if (pressed == pointer.pointerId) {
            val picked = halfStarsForClick(element, pointer.clientX.toDouble())
            if (picked != null && picked != halfStars()) onChange()?.invoke(picked)
        }
    }
    val onEnd: (Event) -> Unit = { pressed = null }
    val listeners =
        mapOf(
            "pointerdown" to onDown,
            "pointermove" to onMove,
            "pointerup" to onEnd,
            "pointercancel" to onEnd,
            "lostpointercapture" to onEnd,
        )
    listeners.forEach { (type, listener) -> element.addEventListener(type, listener) }
    return onDispose { listeners.forEach { (type, listener) -> element.removeEventListener(type, listener) } }
}

/** Pointer capture keeps a drag alive past the stars' edge; a synthetic pointer may refuse it. */
private fun capturePointer(
    element: HTMLElement,
    pointerId: Int,
) {
    try {
        element.asDynamic().setPointerCapture(pointerId)
    } catch (_: Throwable) {
        // Not capturable (a synthetic event, or a pointer already released): the drag still works
        // while the pointer stays over the stars.
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
 * The half-star rating a click at [clientX] on [element] sets, measured from the start edge — the
 * right in a right-to-left page, as Android and iOS mirror the stars — or null before layout.
 */
private fun halfStarsForClick(
    element: HTMLElement,
    clientX: Double,
): Int? {
    val rect = element.getBoundingClientRect()
    if (rect.width <= 0.0) return null
    val fromLeft = clientX - rect.left
    val isRtl = window.getComputedStyle(element).direction == "rtl"
    return halfStarsAt(if (isRtl) rect.width - fromLeft else fromLeft, rect.width)
}

/**
 * The half-star rating a click [x] pixels from the start of five equal stars spanning [width] sets:
 * the first half of a star counts half of it, the second half counts it whole. Clamped to one..five.
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
