package com.calypsan.listenup.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TimeAlignmentTest : FunSpec({

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
        val anchors = listOf(
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
})
