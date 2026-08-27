package com.calypsan.listenup.client.design.timeline

/** Narrowest window the detail lane will zoom to, per the editor spec's ~10-second floor. */
private const val MIN_WINDOW_MS = 10_000L

/**
 * The millisecond↔pixel mapping for one drawn timeline window.
 *
 * Every marker, divider, playhead and drag in the chapter editor resolves through this, which is
 * why it is a plain value class with no Compose types: it is the part worth testing, and it tests
 * far better as arithmetic than as a rendered tree.
 *
 * The problem it exists to solve is scale. A 65-hour book is 234 million milliseconds; drawn whole
 * across 1200px that is roughly 195 seconds per pixel, so **the full-book view is for navigation
 * and never for precision** — the editor reaches for zoom, vertical fine-scrub and snap-to-playhead
 * instead. At the other end the same arithmetic has to stay exact across a ten-second window. Both
 * live here, so both are pinned by the same tests.
 *
 * Positions outside [windowStartMs]..[windowEndMs] map outside `0f..widthPx` deliberately, rather
 * than clamping: the lane clips them, and a clamped coordinate would pile every distant chapter
 * onto the two edges and draw a crowd that isn't there.
 *
 * @property windowStartMs first instant visible in the lane.
 * @property windowEndMs last instant visible in the lane.
 * @property widthPx measured width of the lane. Zero during Compose's first measure pass, which is
 *   why every conversion guards against it rather than emitting `NaN` into the marker positions.
 */
data class TimelineGeometry(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val widthPx: Float,
) {
    /** Length of the visible window. Never negative; zero only for a degenerate window. */
    val windowLengthMs: Long get() = (windowEndMs - windowStartMs).coerceAtLeast(0L)

    /**
     * How much time one pixel covers — the editor's honesty check.
     *
     * A caller deciding whether to offer pixel-precision can ask this instead of guessing from the
     * book's length. Zero when the lane has no width or the window no duration.
     */
    val msPerPixel: Double
        get() = if (widthPx <= 0f || windowLengthMs == 0L) 0.0 else windowLengthMs.toDouble() / widthPx

    /** The x offset [ms] falls at. Outside the window this is negative or past [widthPx], by design. */
    fun xOf(ms: Long): Float {
        if (widthPx <= 0f || windowLengthMs == 0L) return 0f
        return ((ms - windowStartMs).toDouble() / windowLengthMs.toDouble() * widthPx).toFloat()
    }

    /** The instant at x offset [px]. The inverse of [xOf], to within one pixel of time. */
    fun msOf(px: Float): Long {
        if (widthPx <= 0f || windowLengthMs == 0L) return windowStartMs
        return windowStartMs + (px.toDouble() / widthPx * windowLengthMs).toLong()
    }

    /**
     * Slides the window by [deltaPx] of drag, keeping its length and staying inside the book.
     *
     * Dragging content rightwards moves the window earlier, so a positive [deltaPx] subtracts time.
     */
    fun panBy(
        deltaPx: Float,
        bookDurationMs: Long,
    ): TimelineGeometry {
        val shift = (-deltaPx.toDouble() * msPerPixel).toLong()
        return withWindow(windowStartMs + shift, windowLengthMs, bookDurationMs)
    }

    /**
     * Scales the window by [factor] about the instant currently under [focusPx].
     *
     * Anchoring on the focus point is what makes scroll-to-zoom usable: the thing under the cursor
     * has to stay under the cursor, or the gesture fights the user. Clamped to a [MIN_WINDOW_MS]
     * floor and the whole book as a ceiling.
     */
    fun zoomBy(
        factor: Float,
        focusPx: Float,
        bookDurationMs: Long,
    ): TimelineGeometry {
        val focusMs = msOf(focusPx)
        val newLength =
            (windowLengthMs * factor.toDouble())
                .toLong()
                .coerceIn(MIN_WINDOW_MS, bookDurationMs.coerceAtLeast(MIN_WINDOW_MS))
        // Keep the focus instant at the same fraction across the lane.
        val focusFraction = if (widthPx <= 0f) 0.5 else (focusPx / widthPx).toDouble()
        val newStart = focusMs - (newLength * focusFraction).toLong()
        return withWindow(newStart, newLength, bookDurationMs)
    }

    /** Places a window of [lengthMs] starting at [startMs], pushed back inside `0..bookDurationMs`. */
    private fun withWindow(
        startMs: Long,
        lengthMs: Long,
        bookDurationMs: Long,
    ): TimelineGeometry {
        val bounded = lengthMs.coerceAtMost(bookDurationMs.coerceAtLeast(MIN_WINDOW_MS))
        val start = startMs.coerceIn(0L, (bookDurationMs - bounded).coerceAtLeast(0L))
        return copy(windowStartMs = start, windowEndMs = start + bounded)
    }
}
