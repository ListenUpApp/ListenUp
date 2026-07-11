package com.calypsan.listenup.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TimeAlignmentTest :
    FunSpec({

        test("validateAnchors accepts strictly increasing source and target") {
            val anchors = listOf(TimeAnchor(0L, 3_200L), TimeAnchor(100_000L, 148_000L))
            validateAnchors(anchors) shouldBe AnchorValidation.Valid
        }

        test("validateAnchors sorts by sourceMs before checking") {
            val anchors = listOf(TimeAnchor(100_000L, 148_000L), TimeAnchor(0L, 3_200L))
            validateAnchors(anchors) shouldBe AnchorValidation.Valid
        }

        test("validateAnchors rejects an empty list") {
            validateAnchors(emptyList()) shouldBe AnchorValidation.NoAnchors
        }

        test("validateAnchors rejects duplicate sourceMs, naming the segment") {
            val anchors = listOf(TimeAnchor(5_000L, 5_000L), TimeAnchor(5_000L, 9_000L))
            val result = validateAnchors(anchors).shouldBeInstanceOf<AnchorValidation.InvertedSegment>()
            result.segmentIndex shouldBe 0
        }

        test("validateAnchors rejects inverted targets, naming the offending segment") {
            // Sorted by source: (0→10s), (60s→5s), (120s→130s) — segment 0 has target going backwards.
            val anchors =
                listOf(
                    TimeAnchor(0L, 10_000L),
                    TimeAnchor(60_000L, 5_000L),
                    TimeAnchor(120_000L, 130_000L),
                )
            val result = validateAnchors(anchors).shouldBeInstanceOf<AnchorValidation.InvertedSegment>()
            result.segmentIndex shouldBe 0
            result.from shouldBe TimeAnchor(0L, 10_000L)
            result.to shouldBe TimeAnchor(60_000L, 5_000L)
        }

        test("validateAnchors accepts a single anchor") {
            validateAnchors(listOf(TimeAnchor(0L, 42L))) shouldBe AnchorValidation.Valid
        }

        test("identity: zero-offset anchors return timestamps unchanged") {
            val anchors = listOf(TimeAnchor(0L, 0L), TimeAnchor(100_000L, 100_000L))
            val input = listOf(0L, 12_345L, 50_000L, 99_999L, 250_000L)
            alignTimestamps(anchors, input) shouldBe input
        }

        test("one anchor applies a constant shift everywhere") {
            val anchors = listOf(TimeAnchor(10_000L, 13_200L)) // +3.2s
            alignTimestamps(anchors, listOf(0L, 10_000L, 500_000L)) shouldBe
                listOf(3_200L, 13_200L, 503_200L)
        }

        test("two anchors reproduce the affine map from the chapter spec") {
            // f(s) = a·s + b with a = (t_q−t_p)/(s_q−s_p), b = t_p − a·s_p
            // Anchors: (0 → 0), (100_000 → 145_000)  ⇒  a = 1.45, b = 0
            val anchors = listOf(TimeAnchor(0L, 0L), TimeAnchor(100_000L, 145_000L))
            alignTimestamps(anchors, listOf(0L, 40_000L, 100_000L, 200_000L)) shouldBe
                listOf(0L, 58_000L, 145_000L, 290_000L) // extrapolates past the span with the same slope
        }

        test("flat-then-ramp: chapters aligned early, drifting late") {
            // First 10 minutes perfect (offset 0), then drift grows to +45s at 60 minutes.
            val anchors =
                listOf(
                    TimeAnchor(0L, 0L),
                    TimeAnchor(600_000L, 600_000L),
                    TimeAnchor(3_600_000L, 3_645_000L),
                )
            val result =
                alignTimestamps(
                    anchors,
                    listOf(0L, 300_000L, 600_000L, 2_100_000L, 3_600_000L),
                )
            result[0] shouldBe 0L // flat region: untouched
            result[1] shouldBe 300_000L // flat region: untouched
            result[2] shouldBe 600_000L // anchor: exact
            result[3] shouldBe 2_122_500L // halfway up the ramp: +22.5s of the +45s
            result[4] shouldBe 3_645_000L // anchor: exact
        }

        test("anchors land exactly on their targets") {
            val anchors =
                listOf(
                    TimeAnchor(1_000L, 4_200L),
                    TimeAnchor(50_000L, 55_500L),
                    TimeAnchor(90_000L, 99_000L),
                )
            alignTimestamps(anchors, anchors.map { it.sourceMs }) shouldBe anchors.map { it.targetMs }
        }

        test("alignTimestamps rejects invalid anchors") {
            shouldThrow<IllegalArgumentException> {
                alignTimestamps(emptyList(), listOf(0L))
            }
            shouldThrow<IllegalArgumentException> {
                alignTimestamps(
                    listOf(TimeAnchor(0L, 10_000L), TimeAnchor(60_000L, 5_000L)),
                    listOf(0L),
                )
            }
        }
    })
