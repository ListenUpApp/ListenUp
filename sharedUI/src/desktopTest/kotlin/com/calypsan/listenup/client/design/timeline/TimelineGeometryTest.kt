package com.calypsan.listenup.client.design.timeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * [TimelineGeometry] is the pure ms↔px mapping under zoom/pan that every timeline composable
 * reads from — no Compose here, so every case is a plain function call.
 */
class TimelineGeometryTest :
    FunSpec({
        val oneHourMs = 3_600_000L

        test("ms to px to ms round-trips within 1ms at zoom 1") {
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = 1f)
            val samples = listOf(0L, 1_000L, 1_800_000L, oneHourMs - 1, oneHourMs)
            samples.forEach { ms ->
                val px = geo.msToPx(ms)
                val roundTripped = geo.pxToMs(px)
                kotlin.math.abs(roundTripped - ms) shouldBeLessThanOrEqual 1L
            }
        }

        test("ms to px to ms round-trips within 1ms at high zoom") {
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = 20f)
            val samples = listOf(0L, 500L, 100_000L, 1_800_000L)
            samples.forEach { ms ->
                val px = geo.msToPx(ms)
                val roundTripped = geo.pxToMs(px)
                kotlin.math.abs(roundTripped - ms) shouldBeLessThanOrEqual 1L
            }
        }

        test("zoom of 1 fits the whole duration in the viewport") {
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = 1f)
            geo.msToPx(0L) shouldBe 0f
            geo.msToPx(oneHourMs) shouldBe 1000f.plusOrMinus(1f)
        }

        test("zoomedAbout keeps the focal time stationary under the focal pixel") {
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = 1f)
            val focalMs = 1_800_000L // 30 min in
            val focalPx = geo.msToPx(focalMs)

            val zoomed = geo.zoomedAbout(newZoom = 4f, focalMs = focalMs, focalPx = focalPx)

            zoomed.msToPx(focalMs) shouldBe focalPx.plusOrMinus(1f)
        }

        test("zoom is clamped to the 50ms-per-px floor for the given duration and viewport") {
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = 1f)
            val requestedInsane = geo.zoomedAbout(newZoom = 100_000f, focalMs = 0L, focalPx = 0f)
            val maxZoom = TimelineGeometry.maxZoomFor(oneHourMs, 1000f)
            requestedInsane.zoom shouldBe maxZoom
        }

        test("zoom below MIN_ZOOM is rejected by construction") {
            shouldThrowMessage("zoom must be >= 1.0") {
                TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = 0.5f)
            }
        }

        test("pannedBy clamps at the left edge") {
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = 4f, panMs = 0L)
            val pannedLeft = geo.pannedBy(deltaPx = 10_000f) // drag content right = pan time backward, clamps at 0
            pannedLeft.panMs shouldBe 0L
        }

        test("pannedBy clamps at the right edge") {
            val zoom = 4f
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 1000f, zoom = zoom)
            val pannedRight = geo.pannedBy(deltaPx = -1_000_000f)
            val visibleMs = (1000f * (oneHourMs / (1000f * zoom))).toLong()
            pannedRight.panMs shouldBe (oneHourMs - visibleMs).coerceAtLeast(0L)
        }

        test("degenerate duration of 0ms never divides by zero") {
            val geo = TimelineGeometry(durationMs = 0L, viewportWidthPx = 1000f, zoom = 1f)
            geo.msToPx(0L) shouldBe 0f
            geo.pxToMs(500f) shouldBe 0L
        }

        test("degenerate duration of 1ms round-trips") {
            val geo = TimelineGeometry(durationMs = 1L, viewportWidthPx = 1000f, zoom = 1f)
            geo.pxToMs(geo.msToPx(0L)) shouldBe 0L
            geo.pxToMs(geo.msToPx(1L)) shouldBe 1L
        }

        test("65-hour duration (long omnibus) round-trips at zoom 1") {
            val sixtyFiveHoursMs = 65L * 3_600_000L
            val geo = TimelineGeometry(durationMs = sixtyFiveHoursMs, viewportWidthPx = 1200f, zoom = 1f)
            val mid = sixtyFiveHoursMs / 2
            val roundTripped = geo.pxToMs(geo.msToPx(mid))
            kotlin.math.abs(roundTripped - mid) shouldBeLessThanOrEqual 1L
        }

        test("zero viewport width never divides by zero") {
            val geo = TimelineGeometry(durationMs = oneHourMs, viewportWidthPx = 0f, zoom = 1f)
            geo.msToPx(1_000L) shouldBe 0f
        }

        test("bucketDensity groups markers into evenly-sized time buckets across the duration") {
            val geo = TimelineGeometry(durationMs = 100_000L, viewportWidthPx = 1000f, zoom = 1f)
            val markerTimesMs = listOf(1_000L, 2_000L, 50_000L, 99_000L)
            val buckets = geo.bucketDensity(markerTimesMs, bucketCount = 10)
            buckets.size shouldBe 10
            buckets.sum() shouldBe markerTimesMs.size
            buckets[0] shouldBe 2 // 1_000 and 2_000 both fall in the first 10_000ms bucket
        }

        test("bucketDensity on an empty marker list returns all-zero buckets") {
            val geo = TimelineGeometry(durationMs = 100_000L, viewportWidthPx = 1000f, zoom = 1f)
            val buckets = geo.bucketDensity(emptyList(), bucketCount = 5)
            buckets shouldBe listOf(0, 0, 0, 0, 0)
        }

        test("fineScrubSensitivity scales down as vertical displacement grows") {
            val nearZero = TimelineGeometry.fineScrubSensitivity(verticalDisplacementPx = 0f)
            val far = TimelineGeometry.fineScrubSensitivity(verticalDisplacementPx = 300f)
            nearZero shouldBe 1f
            (far < nearZero) shouldBe true
            (far > 0f) shouldBe true // never fully locks, always some movement
        }

        test("fineScrubSensitivity clamps at a floor so a marker never becomes un-draggable") {
            val extreme = TimelineGeometry.fineScrubSensitivity(verticalDisplacementPx = 10_000f)
            (extreme >= TimelineGeometry.MIN_FINE_SCRUB_SENSITIVITY) shouldBe true
        }
    })

private fun shouldThrowMessage(
    substring: String,
    block: () -> Unit,
) {
    val thrown =
        try {
            block()
            null
        } catch (e: IllegalArgumentException) {
            e
        }
    kotlin.requireNotNull(thrown) { "expected IllegalArgumentException" }
    (thrown.message ?: "").contains(substring) shouldBe true
}
