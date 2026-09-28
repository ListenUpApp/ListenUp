package com.calypsan.listenup.client.design.motion

import com.calypsan.listenup.client.design.util.BackGestureEdge
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * A full-screen surface under predictive back previews Material's way: it shrinks toward
 * [PREDICTIVE_BACK_SCALE], drifts away from the swipe's edge and rounds its corners, and stays
 * opaque the whole way, so the page beneath never shows through it.
 */
class PredictiveBackPreviewTest :
    FunSpec({
        val width = 1000f // drift is width / 20 less the 10px margin: 40px at full progress
        val margin = 10f

        test("the surface stays opaque mid-gesture and at the end of it") {
            predictiveBackPreview(0.5f, BackGestureEdge.Left, width, margin).alpha shouldBe 1f
            predictiveBackPreview(1f, BackGestureEdge.Right, width, margin).alpha shouldBe 1f
        }

        test("the surface shrinks toward Material's preview scale") {
            predictiveBackPreview(0f, BackGestureEdge.Left, width, margin).scale shouldBe 1f
            predictiveBackPreview(0.5f, BackGestureEdge.Left, width, margin).scale shouldBe (0.95f plusOrMinus 1e-4f)
            predictiveBackPreview(1f, BackGestureEdge.Left, width, margin).scale shouldBe PREDICTIVE_BACK_SCALE
        }

        test("the surface drifts away from the edge the swipe started at") {
            predictiveBackPreview(1f, BackGestureEdge.Left, width, margin).translationX shouldBe 40f
            predictiveBackPreview(1f, BackGestureEdge.Right, width, margin).translationX shouldBe -40f
            predictiveBackPreview(0.5f, BackGestureEdge.Left, width, margin).translationX shouldBe 20f
            predictiveBackPreview(1f, BackGestureEdge.None, width, margin).translationX shouldBe 0f
        }

        test("the corners round in step with the gesture") {
            predictiveBackPreview(0f, BackGestureEdge.Left, width, margin).cornerFraction shouldBe 0f
            predictiveBackPreview(0.5f, BackGestureEdge.Left, width, margin).cornerFraction shouldBe 0.5f
        }
    })
