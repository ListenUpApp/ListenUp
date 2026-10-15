package com.calypsan.listenup.client.design.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageDecoderEnabled
import coil3.request.crossfade
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.design.components.ContributorCoverImage
import io.kotest.matchers.ints.shouldBeLessThan
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The app's image loader crossfades every image, and Coil's crossfade painter applies the image's content
 * scale itself before the image applies it again. For a plain crop that second pass is a no-op; for a
 * frame it squared the zoom and dropped the alignment, so a 2x frame drew at 4x around the wrong point.
 * A framed photo must draw exactly as the frame says even with crossfade on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FramedCrossfadeTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        restoreCoil()
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun `a framed photo draws as its frame says when the loader crossfades`() {
        SingletonImageLoader.setUnsafe(
            ImageLoader
                .Builder(RuntimeEnvironment.getApplication())
                .imageDecoderEnabled(false)
                .crossfade(true)
                .build(),
        )
        val file = gradientPhoto()
        val bitmap = BitmapFactory.decodeFile(file.path).asImageBitmap()
        val frame = ImageFrame(focusX = 0.3, focusY = 0.2, zoom = 2.0)

        compose.setContent {
            Row {
                Box(Modifier.testTag("expected")) {
                    Image(
                        painter = BitmapPainter(bitmap),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                        alignment = FramedAlignment(frame),
                        contentScale = FramedCrop(frame),
                    )
                }
                Box(Modifier.testTag("actual")) {
                    ContributorCoverImage(
                        contributorId = "c1",
                        imagePath = null,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                        stagingImagePath = file.path,
                        frame = frame,
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose
                .onNodeWithTag("actual")
                .captureToImage()
                .toPixelMap()[60, 60]
                .alpha > 0.5f
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        val expected = compose.onNodeWithTag("expected").captureToImage().toPixelMap()
        val actual = compose.onNodeWithTag("actual").captureToImage().toPixelMap()
        var worst = 0
        for (x in 0 until expected.width step 7) {
            for (y in 0 until expected.height step 7) {
                val e = expected[x, y]
                val a = actual[x, y]
                worst = maxOf(worst, (abs(e.red - a.red) * 255).toInt(), (abs(e.green - a.green) * 255).toInt())
            }
        }
        worst shouldBeLessThan 24
    }

    /** A 300×400 photo whose red runs left to right and green top to bottom, so any shift or zoom shows. */
    private fun gradientPhoto(): File {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        for (x in 0 until 300) {
            for (y in 0 until 400) {
                bitmap.setPixel(x, y, android.graphics.Color.rgb(x * 255 / 299, y * 255 / 399, 128))
            }
        }
        return File.createTempFile("gradient", ".png").apply {
            deleteOnExit()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
