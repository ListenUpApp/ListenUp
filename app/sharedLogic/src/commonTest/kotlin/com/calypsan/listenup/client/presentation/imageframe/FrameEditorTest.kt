package com.calypsan.listenup.client.presentation.imageframe

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.NudgeDirection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class FrameEditorTest :
    FunSpec({
        test("a photo opens at its saved frame, made canonical, or centred when it has none") {
            FrameEditor(300, 400, null, FrameShape.Circle).state.value shouldBe
                FrameEditorState.Ready(300, 400, ImageFrame.Centered, FrameShape.Circle)
            // Focus at the very top of a portrait: the top edge is flush, so what shows is 0.375 down.
            FrameEditor(300, 400, ImageFrame(0.5, 0.0, 1.0), FrameShape.Circle)
                .state.value.frame.focusY shouldBe
                (0.375 plusOrMinus 1e-12)
        }

        test("nothing moves before the platform has measured the editor") {
            val editor = FrameEditor(1_000, 1_000, ImageFrame(0.5, 0.5, 2.0), FrameShape.Circle)
            editor.pan(10.0, 10.0)
            editor.state.value.frame shouldBe ImageFrame(0.5, 0.5, 2.0)
        }

        test("each step goes through the shared maths: pan, zoom, nudge Up, reset") {
            val editor = FrameEditor(1_000, 1_000, null, FrameShape.Circle)
            editor.setViewport(100.0)
            editor.zoomTo(2.0)
            editor.state.value.frame.zoom shouldBe (2.0 plusOrMinus 1e-12)
            editor.nudge(NudgeDirection.Up, large = false)
            editor.state.value.frame.focusY shouldBeLessThan 0.5
            editor.zoomBy(1.5, 50.0, 50.0)
            editor.state.value.frame.zoom shouldBe (3.0 plusOrMinus 1e-12)
            editor.reset()
            editor.state.value.frame shouldBe ImageFrame.Centered
        }

        test("feedback fires once when a drag first meets an edge, not on every frame pinned there") {
            runTest {
                val editor = FrameEditor(1_000, 1_000, ImageFrame(0.5, 0.5, 2.0), FrameShape.Circle)
                editor.setViewport(100.0)
                editor.feedback.test {
                    editor.pan(500.0, 0.0)
                    editor.pan(5.0, 0.0)
                    editor.pan(5.0, 0.0)
                    awaitItem() shouldBe FrameFeedback.EdgeReached
                    expectNoEvents()

                    editor.pan(-20.0, 0.0) // back inside
                    editor.pan(500.0, 0.0) // and out again
                    awaitItem() shouldBe FrameFeedback.EdgeReached
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("feedback fires once when a zoom first meets a limit") {
            runTest {
                val editor = FrameEditor(1_000, 1_000, null, FrameShape.Circle)
                editor.setViewport(100.0)
                editor.feedback.test {
                    editor.zoomTo(9.0)
                    editor.zoomBy(2.0, 50.0, 50.0)
                    awaitItem() shouldBe FrameFeedback.ZoomLimitReached
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an image with no size is clamped, never thrown") {
            FrameEditor(0, -5, null, FrameShape.Square).state.value shouldBe
                FrameEditorState.Ready(1, 1, ImageFrame.Centered, FrameShape.Square)
        }
    })
