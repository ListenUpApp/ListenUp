package com.calypsan.listenup.client.foldable

import androidx.compose.ui.unit.IntRect
import androidx.window.layout.FoldingFeature
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Tests for [classifyFold]: the posture plus where the hinge lies in the window. */
class FoldClassificationTest :
    FunSpec({
        val hinge = IntRect(0, 1000, 1800, 1040)

        test("a half-open horizontal fold reports tabletop and the hinge's window bounds") {
            classifyFold(
                FoldingFeature.State.HALF_OPENED,
                FoldingFeature.Orientation.HORIZONTAL,
                hinge,
            ) shouldBe Fold(Posture.TABLETOP, hinge)
        }

        test("a flat fold still reports where the hinge is") {
            classifyFold(FoldingFeature.State.FLAT, FoldingFeature.Orientation.HORIZONTAL, hinge).hingeBounds shouldBe hinge
        }

        test("no folding feature is no fold") {
            classifyFold(state = null, orientation = null, bounds = null) shouldBe Fold.None
        }
    })
