package com.calypsan.listenup.client.playback

import android.graphics.Bitmap
import android.net.Uri
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A cover miss must not decode on the caller's (main) thread: it answers nothing now and republishes when ready. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationArtworkCacheTest {
    private val cover = Uri.parse("content://covers/book-1")

    private fun artwork(
        scope: TestScope,
        bitmap: Bitmap?,
        decodes: MutableList<Uri>,
    ) = NotificationArtworkCache(
        scope = scope,
        decodeDispatcher = StandardTestDispatcher(scope.testScheduler),
        decode = { uri ->
            decodes += uri
            bitmap
        },
    )

    @Test
    fun `a miss answers nothing at once, decodes later, then reports the cover`() =
        runTest {
            val decodes = mutableListOf<Uri>()
            val decoded = mutableListOf<Uri>()
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val artwork = artwork(this, bitmap, decodes)

            artwork.load(cover) { decoded += it }.shouldBeNull()
            decodes shouldBe emptyList()

            testScheduler.advanceUntilIdle()

            decodes shouldBe listOf(cover)
            decoded shouldBe listOf(cover)
        }

    @Test
    fun `a hit answers the cover at once and decodes nothing`() =
        runTest {
            val decodes = mutableListOf<Uri>()
            val decoded = mutableListOf<Uri>()
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val artwork = artwork(this, bitmap, decodes)
            artwork.load(cover) { }
            testScheduler.advanceUntilIdle()

            artwork.load(cover) { decoded += it } shouldBe bitmap

            testScheduler.advanceUntilIdle()
            decodes shouldBe listOf(cover)
            decoded shouldBe emptyList()
        }

    @Test
    fun `ticks arriving mid-decode do not start a second decode`() =
        runTest {
            val decodes = mutableListOf<Uri>()
            val artwork = artwork(this, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), decodes)

            artwork.load(cover) { }
            artwork.load(cover) { }
            testScheduler.advanceUntilIdle()

            decodes shouldBe listOf(cover)
        }

    @Test
    fun `a cover that cannot be decoded reports nothing`() =
        runTest {
            val decoded = mutableListOf<Uri>()
            val artwork = artwork(this, null, mutableListOf())

            artwork.load(cover) { decoded += it }.shouldBeNull()
            testScheduler.advanceUntilIdle()

            decoded shouldBe emptyList()
        }
}
