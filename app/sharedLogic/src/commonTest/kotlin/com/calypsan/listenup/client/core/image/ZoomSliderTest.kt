package com.calypsan.listenup.client.core.image

import com.calypsan.listenup.api.dto.image.ImageFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll

class ZoomSliderTest :
    FunSpec({
        test("the ends of the slider are the ends of the zoom range") {
            zoomForSliderPosition(0.0) shouldBe (ImageFrame.MIN_ZOOM plusOrMinus 1e-12)
            zoomForSliderPosition(1.0) shouldBe (ImageFrame.MAX_ZOOM plusOrMinus 1e-12)
        }

        test("the middle of the slider is 2x, so each half doubles the zoom") {
            zoomForSliderPosition(0.5) shouldBe (2.0 plusOrMinus 1e-12)
        }

        test("position and zoom round-trip") {
            checkAll(Arb.double(0.0, 1.0, includeNaNs = false)) { p ->
                sliderPositionForZoom(zoomForSliderPosition(p)) shouldBe (p plusOrMinus 1e-9)
            }
        }

        test("out-of-range and NaN inputs clamp rather than throw") {
            zoomForSliderPosition(-3.0) shouldBe (1.0 plusOrMinus 1e-12)
            zoomForSliderPosition(9.0) shouldBe (4.0 plusOrMinus 1e-12)
            zoomForSliderPosition(Double.NaN) shouldBe (1.0 plusOrMinus 1e-12)
            sliderPositionForZoom(0.2) shouldBe (0.0 plusOrMinus 1e-12)
            sliderPositionForZoom(Double.NaN) shouldBe (0.0 plusOrMinus 1e-12)
        }

        test("a zoom label never loses its decimal: 1.0x reads 1.0x, not 1x") {
            zoomLabel(1.0) shouldBe "1.0×"
            zoomLabel(2.249) shouldBe "2.2×"
            zoomLabel(4.0) shouldBe "4.0×"
        }
    })
