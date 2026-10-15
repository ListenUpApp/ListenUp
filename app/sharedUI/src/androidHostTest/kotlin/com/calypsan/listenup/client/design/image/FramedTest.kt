package com.calypsan.listenup.client.design.image

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.frameLayout
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.roundToInt

class FramedTest :
    FunSpec({
        val portrait = Size(300f, 400f)
        val circle = Size(120f, 120f)

        test("no frame scales exactly like ContentScale.Crop") {
            FramedCrop(null).computeScaleFactor(portrait, circle) shouldBe
                ContentScale.Crop.computeScaleFactor(portrait, circle)
        }

        test("no frame aligns exactly like Alignment.Center: today's centre crop") {
            val scaled = IntSize(120, 160)
            FramedAlignment(null).align(scaled, IntSize(120, 120), LayoutDirection.Ltr) shouldBe IntOffset(0, -20)
        }

        test("a frame's scale is frameLayout's scale") {
            val frame = ImageFrame(focusX = 0.5, focusY = 0.2, zoom = 2.2)
            val expected = frameLayout(300.0, 400.0, 120.0, 120.0, frame).scale.toFloat()
            FramedCrop(frame).computeScaleFactor(portrait, circle).scaleX shouldBe (expected plusOrMinus 1e-5f)
        }

        test("a frame's offset is frameLayout's offset, computed from the already-scaled size") {
            val frame = ImageFrame(focusX = 0.5, focusY = 0.2, zoom = 2.2)
            val layout = frameLayout(300.0, 400.0, 120.0, 120.0, frame)
            val scaled = IntSize(layout.width.roundToInt(), layout.height.roundToInt())
            FramedAlignment(frame).align(scaled, IntSize(120, 120), LayoutDirection.Ltr) shouldBe
                IntOffset(layout.offsetX.roundToInt(), layout.offsetY.roundToInt())
        }

        test("right-to-left does not mirror a frame: a face is not text") {
            val frame = ImageFrame(focusX = 0.1, focusY = 0.5, zoom = 2.0)
            val scaled = IntSize(480, 640)
            FramedAlignment(frame).align(scaled, IntSize(120, 120), LayoutDirection.Rtl) shouldBe
                FramedAlignment(frame).align(scaled, IntSize(120, 120), LayoutDirection.Ltr)
        }

        test("an image with no intrinsic size is left alone rather than scaled by NaN") {
            FramedCrop(ImageFrame.Centered).computeScaleFactor(Size.Unspecified, circle).scaleX shouldBe 1f
        }

        test("equal frames are equal, so Compose does not re-layout on every recomposition") {
            FramedCrop(ImageFrame(0.3, 0.3, 1.5)) shouldBe FramedCrop(ImageFrame(0.3, 0.3, 1.5))
            FramedAlignment(ImageFrame(0.3, 0.3, 1.5)) shouldBe FramedAlignment(ImageFrame(0.3, 0.3, 1.5))
        }

        test("the decode size grows with the zoom, so a zoomed face is not an upscaled thumbnail") {
            zoomedPixels(base = 360, zoom = 2.2) shouldBe 792
            zoomedPixels(base = 360, zoom = null) shouldBe 360
            zoomedPixels(base = 1200, zoom = 4.0) shouldBe 2048
        }
    })
