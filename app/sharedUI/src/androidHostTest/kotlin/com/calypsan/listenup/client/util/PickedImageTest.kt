package com.calypsan.listenup.client.util

import android.net.Uri
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for the picked-image pipeline.
 *
 * The sample-size decision is pure and proven without Android. The dispatch tests run under
 * Robolectric, which cannot rotate pixels, so EXIF orientation itself (delegated to
 * `ImageDecoder`) is verified on a device, not here.
 */
@RunWith(RobolectricTestRunner::class)
class PickedImageTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newState(onResult: (ImagePickerResult) -> Unit = {}) =
        ImagePickerState(RuntimeEnvironment.getApplication(), scope, onResult)

    private fun unopenableUri(): Uri = Uri.parse("content://com.calypsan.listenup.test.missing/image/1")

    private fun awaitResult(deferred: CompletableDeferred<ImagePickerResult>): ImagePickerResult =
        runBlocking { withTimeout(30_000) { deferred.await() } }

    @Test
    fun `a 50 megapixel photo is sampled down to the decode cap`() {
        val sample = pickedImageSampleSize(8160, 6120)
        8160 / sample shouldBeLessThanOrEqual PICKED_IMAGE_MAX_EDGE_PX
    }

    @Test
    fun `an image already within the cap is not resampled`() {
        pickedImageSampleSize(1200, 800) shouldBe 1
    }

    @Test
    fun `bounds that failed to decode sample at one`() {
        pickedImageSampleSize(0, 0) shouldBe 1
    }

    @Test
    fun `cancelling the picker delivers Cancelled`() {
        val delivered = CompletableDeferred<ImagePickerResult>()
        newState { delivered.complete(it) }.handleResult(null)

        awaitResult(delivered) shouldBe ImagePickerResult.Cancelled
    }

    @Test
    fun `a URI that cannot be opened delivers an Error and does not throw`() {
        val delivered = CompletableDeferred<ImagePickerResult>()
        newState { delivered.complete(it) }.handleResult(unopenableUri())

        awaitResult(delivered).shouldBeInstanceOf<ImagePickerResult.Error>()
    }

    @Test
    fun `a replaced callback receives the result and the original does not`() {
        val original = CompletableDeferred<ImagePickerResult>()
        val replacement = CompletableDeferred<ImagePickerResult>()
        val state = newState { original.complete(it) }
        state.onResult = { replacement.complete(it) }

        state.handleResult(unopenableUri())

        awaitResult(replacement).shouldBeInstanceOf<ImagePickerResult.Error>()
        original.isCompleted shouldBe false
    }
}
