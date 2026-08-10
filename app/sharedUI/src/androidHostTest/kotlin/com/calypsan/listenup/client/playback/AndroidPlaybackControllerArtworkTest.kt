package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.core.BookId
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AndroidPlaybackController.buildMediaItems]' artwork URI mapping.
 *
 * Split out from [AndroidPlaybackControllerTest] because this path — unlike the rest of the
 * controller's pure logic — calls `CoverUri.forBook`, which builds a real [android.net.Uri] via
 * [android.net.Uri.Builder]. That framework class throws outside a Robolectric runtime
 * (`Method scheme in android.net.Uri$Builder not mocked`), so this class uses
 * [RobolectricTestRunner] + JUnit4 with Kotest matchers — the same shape as `CoverUriTest` and
 * `BrowseTreeProviderTest` — rather than the plain Kotest [io.kotest.core.spec.style.FunSpec]
 * the sibling spec uses for everything that doesn't touch `Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidPlaybackControllerArtworkTest {
    @Test
    fun `buildMediaItems sets a content uri for artwork`() {
        val sut = AndroidPlaybackController(FakeControllerHolder(), PACKAGE_NAME)

        val items = sut.buildMediaItems(prepareResultFor("book-1"))

        items.first().artworkUri shouldBe
            "content://com.calypsan.listenup.client.covers/covers/book-1"
    }

    @Test
    fun `buildMediaItems never emits a file uri for artwork`() {
        val sut = AndroidPlaybackController(FakeControllerHolder(), PACKAGE_NAME)

        val items = sut.buildMediaItems(prepareResultFor("book-1"))

        items.forEach { item ->
            item.artworkUri?.startsWith("file://") shouldBe false
        }
    }

    @Test
    fun `buildMediaItems carries every file in the timeline`() {
        val sut = AndroidPlaybackController(FakeControllerHolder(), PACKAGE_NAME)

        val items = sut.buildMediaItems(prepareResultFor("book-1", fileCount = 3))

        items.map { it.mediaId } shouldBe listOf("af-0", "af-1", "af-2")
    }
}

private const val PACKAGE_NAME = "com.calypsan.listenup.client"

private fun prepareResultFor(
    bookId: String,
    fileCount: Int = 1,
): PlaybackManager.PrepareResult =
    PlaybackManager.PrepareResult(
        timeline =
            PlaybackTimeline(
                bookId = BookId(bookId),
                totalDurationMs = 3_600_000L * fileCount,
                files =
                    (0 until fileCount).map { index ->
                        PlaybackTimeline.FileSegment(
                            audioFileId = "af-$index",
                            filename = "part$index.m4b",
                            format = "m4b",
                            startOffsetMs = 3_600_000L * index,
                            durationMs = 3_600_000L,
                            size = 1_024L,
                            streamingUrl = "https://example.test/af-$index",
                            localPath = null,
                            mediaItemIndex = index,
                        )
                    },
            ),
        bookTitle = "The Way of Kings",
        bookAuthor = "Brandon Sanderson",
        seriesName = "The Stormlight Archive",
        coverPath = "/data/user/0/com.calypsan.listenup.client/files/covers/$bookId.jpg",
        totalChapters = 1,
        resumePositionMs = 0L,
        resumeSpeed = 1.0f,
        resumeBoostDb = 0f,
        measuredGainDb = null,
        normalizationGainDb = null,
    )
