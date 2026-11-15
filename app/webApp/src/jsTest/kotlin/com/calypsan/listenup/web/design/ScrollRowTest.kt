package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A scroll row knows which end it is at, with a pixel of slack for fractional layouts. */
class ScrollRowTest :
    FunSpec({
        test("a row that fits has nowhere to go") {
            scrollRowPosition(scrollLeft = 0.0, clientWidth = 800.0, scrollWidth = 800.0) shouldBe ScrollRowPosition.Fits
            scrollRowPosition(scrollLeft = 0.0, clientWidth = 800.0, scrollWidth = 800.5) shouldBe ScrollRowPosition.Fits
        }

        test("a wider row is at its start, part-way, or at its end") {
            scrollRowPosition(0.0, 800.0, 1700.0) shouldBe ScrollRowPosition.Start
            scrollRowPosition(400.0, 800.0, 1700.0) shouldBe ScrollRowPosition.Middle
            scrollRowPosition(899.5, 800.0, 1700.0) shouldBe ScrollRowPosition.End
        }

        test("back is open past the start and forward before the end") {
            ScrollRowPosition.Start.canGoBack shouldBe false
            ScrollRowPosition.Start.canGoForward shouldBe true
            ScrollRowPosition.Middle.canGoBack shouldBe true
            ScrollRowPosition.Middle.canGoForward shouldBe true
            ScrollRowPosition.End.canGoBack shouldBe true
            ScrollRowPosition.End.canGoForward shouldBe false
            ScrollRowPosition.Fits.canGoBack shouldBe false
            ScrollRowPosition.Fits.canGoForward shouldBe false
        }
    })
