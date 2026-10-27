package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class PlayerMorphTimelineTest :
    FunSpec({
        test("the windows are the canvas's") {
            MorphElement.entries.associateWith { it.window } shouldBe
                mapOf(
                    MorphElement.NavigationBar to MorphWindow(0f, 0.4f),
                    MorphElement.BarSecondLine to MorphWindow(0f, 0.25f),
                    MorphElement.BarSkipBack to MorphWindow(0f, 0.2f),
                    MorphElement.SheetColour to MorphWindow(0f, 0.6f),
                    MorphElement.ScrubberThickness to MorphWindow(0.3f, 1f),
                    MorphElement.ScrubberThumb to MorphWindow(0.75f, 1f),
                    MorphElement.Header to MorphWindow(0.65f, 1f),
                    MorphElement.Chapter to MorphWindow(0.55f, 0.9f),
                    MorphElement.Narrator to MorphWindow(0.6f, 0.95f),
                    MorphElement.Times to MorphWindow(0.6f, 0.95f),
                    MorphElement.TransportSides to MorphWindow(0.55f, 0.9f),
                    MorphElement.Settings to MorphWindow(0.65f, 1f),
                )
        }

        test("ease-out cubic inside a window, flat outside it") {
            reveal(MorphElement.Header, 0.6f) shouldBe 0f
            reveal(MorphElement.Header, 0.825f) shouldBe (0.875f plusOrMinus 1e-4f) // 1 − (1 − 0.5)^3
            reveal(MorphElement.Header, 1.05f) shouldBe 1f
        }

        test("the sheet colour is linear in its window") {
            reveal(MorphElement.SheetColour, 0.3f) shouldBe (0.5f plusOrMinus 1e-4f)
        }

        test("leaving elements read the window as how far they have gone") {
            fade(MorphElement.BarSecondLine, 0.125f) shouldBe (1f - 0.875f plusOrMinus 1e-4f)
        }

        test("the sheet's top corners rise and fall with sin(πp), 0 at both ends") {
            sheetCornerFraction(0f) shouldBe 0f
            sheetCornerFraction(1f) shouldBe (0f plusOrMinus 1e-6f)
            sheetCornerFraction(0.5f) shouldBe (1f plusOrMinus 1e-6f)
        }

        test("under Crossfade every element is at rest, and only the whole player's opacity follows p") {
            reveal(MorphElement.Header, 0.3f, MorphMode.Crossfade) shouldBe 1f
            fade(MorphElement.BarSecondLine, 0.3f, MorphMode.Crossfade) shouldBe 1f
            playerOpacity(0.3f, MorphMode.Crossfade) shouldBe 0.3f
            playerOpacity(0.3f, MorphMode.Morph) shouldBe 1f
        }

        test("transport sides grow from 85 %") {
            sidesScale(0.55f) shouldBe 0.85f
            sidesScale(0.9f) shouldBe 1f
        }

        test("the title switches weight (and on web its face) at halfway") {
            titleIsLarge(0.49f) shouldBe false
            titleIsLarge(0.51f) shouldBe true
        }
    })
