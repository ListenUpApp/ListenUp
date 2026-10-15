package com.calypsan.listenup.client.design.image

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** What the editor opens: a picked photo decodes to its real size; bytes that are not a photo cannot be opened. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameEditorImageTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun decodeFiles() {
        decodePhotosWithBitmapFactory()
    }

    @After
    fun restore() {
        restoreCoil()
    }

    private fun open(source: FrameEditorSource): () -> FrameEditorImage {
        var image by mutableStateOf<FrameEditorImage>(FrameEditorImage.Loading)
        compose.setContent { image = rememberFrameEditorImage(source) }
        return { image }
    }

    @Test
    fun `a picked photo decodes to its own size`() {
        val png =
            ByteArrayOutputStream()
                .also { Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        val image = open(FrameEditorSource.Bytes(png))
        compose.waitUntil(timeoutMillis = 5_000) { image() !is FrameEditorImage.Loading }
        val ready = image().shouldBeInstanceOf<FrameEditorImage.Ready>()
        (ready.width to ready.height) shouldBe (30 to 40)
    }

    @Test
    fun `bytes that are not a photo cannot be opened`() {
        val image = open(FrameEditorSource.Bytes("not a photo".encodeToByteArray()))
        compose.waitUntil(timeoutMillis = 5_000) { image() !is FrameEditorImage.Loading }
        image() shouldBe FrameEditorImage.CouldNotOpen
    }

    @Test
    fun `a pick that failed to decode opens straight to could-not-open`() {
        val image = open(FrameEditorSource.Undecodable)
        image() shouldBe FrameEditorImage.CouldNotOpen
    }

    @Test
    fun `a photo still being resolved is loading`() {
        val image = open(FrameEditorSource.Resolving)
        image() shouldBe FrameEditorImage.Loading
    }

    @Test
    fun `an existing photo on disk opens at its own size`() {
        val image = open(FrameEditorSource.Model(pngFile(20, 10).absolutePath))
        compose.waitUntil(timeoutMillis = 5_000) { image() !is FrameEditorImage.Loading }
        val ready = image().shouldBeInstanceOf<FrameEditorImage.Ready>()
        (ready.width to ready.height) shouldBe (20 to 10)
    }
}
