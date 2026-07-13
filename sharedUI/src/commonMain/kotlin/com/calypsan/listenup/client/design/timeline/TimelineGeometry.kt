package com.calypsan.listenup.client.design.timeline

/**
 * Pure time↔pixel mapping for a `MarkerLaneTimeline` under zoom and pan. [durationMs] is the
 * book's total length; [viewportWidthPx] is the visible lane width; [zoom] is a multiplier ≥ 1
 * (1 = the whole duration fits the viewport); [panMs] is the time offset of the viewport's left
 * edge. No Compose dependency — plain math, unit-tested here and consumed by the composables.
 */
data class TimelineGeometry(
    val durationMs: Long,
    val viewportWidthPx: Float,
    val zoom: Float = 1f,
    val panMs: Long = 0L,
) {
    init {
        require(durationMs >= 0) { "durationMs must be >= 0" }
        require(viewportWidthPx >= 0f) { "viewportWidthPx must be >= 0" }
        require(zoom >= MIN_ZOOM) { "zoom must be >= $MIN_ZOOM" }
    }

    /** Pixels the full [durationMs] spans at the current [zoom] (independent of viewport). */
    private val totalContentPx: Float
        get() = if (durationMs == 0L) 0f else viewportWidthPx * zoom

    /** Milliseconds represented by one pixel at the current zoom; guards a zero-duration book. */
    private val msPerPx: Float
        get() = if (totalContentPx <= 0f) 0f else durationMs.toFloat() / totalContentPx

    /** Maps an absolute [timeMs] to an x pixel offset relative to the viewport's left edge. */
    fun msToPx(timeMs: Long): Float {
        if (msPerPx <= 0f) return 0f
        return (timeMs - panMs) / msPerPx
    }

    /** Maps an x pixel offset (relative to the viewport's left edge) back to absolute ms. */
    fun pxToMs(px: Float): Long {
        if (durationMs == 0L) return 0L
        val raw = panMs + (px * msPerPx).toLong()
        return raw.coerceIn(0L, durationMs)
    }

    /** Zooms so [focalMs] stays under the same [focalPx] after the zoom change — pinch/double-tap idiom. */
    fun zoomedAbout(
        newZoom: Float,
        focalMs: Long,
        focalPx: Float,
    ): TimelineGeometry {
        val clampedZoom = newZoom.coerceIn(MIN_ZOOM, maxZoomFor(durationMs, viewportWidthPx))
        val next = copy(zoom = clampedZoom)
        // Solve for the panMs that keeps focalMs under focalPx at the new zoom.
        val newMsPerPx = next.msPerPx
        val newPanMs = if (newMsPerPx <= 0f) 0L else focalMs - (focalPx * newMsPerPx).toLong()
        return next.copy(panMs = newPanMs.coerceIn(0L, maxPanMs(durationMs, next)))
    }

    /** Pans by [deltaPx], clamped so the viewport never scrolls past the content bounds. */
    fun pannedBy(deltaPx: Float): TimelineGeometry {
        if (msPerPx <= 0f) return this
        val deltaMs = (deltaPx * msPerPx).toLong()
        return copy(panMs = (panMs - deltaMs).coerceIn(0L, maxPanMs(durationMs, this)))
    }

    /** Buckets [markerTimesMs] into [bucketCount] evenly-sized time windows — minimap density shading. */
    fun bucketDensity(
        markerTimesMs: List<Long>,
        bucketCount: Int,
    ): List<Int> {
        val buckets = MutableList(bucketCount) { 0 }
        if (durationMs <= 0L) return buckets
        val bucketWidthMs = durationMs.toDouble() / bucketCount
        markerTimesMs.forEach { ms ->
            val idx = (ms / bucketWidthMs).toInt().coerceIn(0, bucketCount - 1)
            buckets[idx] = buckets[idx] + 1
        }
        return buckets
    }

    companion object {
        /** zoom = 1 always fits; no lower bound below "whole book visible". */
        const val MIN_ZOOM = 1f

        /** Fine-editing resolution floor: 1 px must never represent less than 50 ms. */
        const val MIN_MS_PER_PX = 50f

        /** Floor for [fineScrubSensitivity] — a marker always retains at least 5% drag sensitivity. */
        const val MIN_FINE_SCRUB_SENSITIVITY = 0.05f

        /** Sensitivity floor beyond which added vertical displacement stops shrinking it further. */
        private const val FINE_SCRUB_FLOOR_DISPLACEMENT_PX = 240f

        /** The zoom at which [MIN_MS_PER_PX] is reached for this duration/viewport pair. */
        fun maxZoomFor(
            durationMs: Long,
            viewportWidthPx: Float,
        ): Float {
            if (durationMs <= 0L || viewportWidthPx <= 0f) return MIN_ZOOM
            val zoomAtFloor = durationMs / (MIN_MS_PER_PX * viewportWidthPx)
            return zoomAtFloor.coerceAtLeast(MIN_ZOOM)
        }

        /**
         * Vertical fine-scrub idiom: the further the drag has moved from the horizontal axis, the
         * finer (slower) horizontal movement becomes — 1.0 at 0px, decaying to [MIN_FINE_SCRUB_SENSITIVITY]
         * by [FINE_SCRUB_FLOOR_DISPLACEMENT_PX].
         */
        fun fineScrubSensitivity(verticalDisplacementPx: Float): Float {
            val t = (kotlin.math.abs(verticalDisplacementPx) / FINE_SCRUB_FLOOR_DISPLACEMENT_PX).coerceIn(0f, 1f)
            return (1f - t * (1f - MIN_FINE_SCRUB_SENSITIVITY)).coerceAtLeast(MIN_FINE_SCRUB_SENSITIVITY)
        }

        private fun maxPanMs(
            durationMs: Long,
            geometry: TimelineGeometry,
        ): Long {
            val visibleMs = (geometry.viewportWidthPx * geometry.msPerPx).toLong()
            return (durationMs - visibleMs).coerceAtLeast(0L)
        }
    }
}
