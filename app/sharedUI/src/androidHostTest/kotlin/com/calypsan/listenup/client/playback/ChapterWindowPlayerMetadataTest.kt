package com.calypsan.listenup.client.playback

import androidx.media3.common.MediaMetadata
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.client.localization.SystemStrings
import com.calypsan.listenup.core.BookId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the system media card reads from the session (spec §5.7): Android 13+ draws the shade and
 * lock-screen player from the session's metadata, so the book, the chapter with its time left, and the
 * author must be there, not only in the notification's own text.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterWindowPlayerMetadataTest {
    private val timeline =
        PlaybackTimeline.build(
            bookId = BookId("way-of-kings"),
            files = listOf(TimelineFileInput("af-1", "book.m4b", "m4b", 9_000_000L, 1L, null, "https://s/1")),
        )

    private val bookMetadata =
        MediaMetadata
            .Builder()
            .setTitle("The Way of Kings")
            .setArtist("Brandon Sanderson")
            .setAlbumTitle("The Stormlight Archive")
            .build()

    private val chapters =
        (1..75).map { number ->
            Chapter(
                id = "c$number",
                title = if (number == 6) "Bridge Four" else "Chapter $number",
                duration = 100_000L,
                startTime = (number - 1) * 100_000L,
            )
        }

    private fun presented(
        chapters: List<Chapter>,
        positionMs: Long,
    ): MediaMetadata {
        val fake = FakeExoPlayer(stubbedPosition = positionMs, stubbedMetadata = bookMetadata)
        val wrapper =
            ChapterWindowPlayer(
                player = fake,
                chaptersProvider = { chapters },
                timelineProvider = { timeline },
                strings = { SystemStrings.ENGLISH_FALLBACK },
            )
        return wrapper.mediaMetadata
    }

    @Test
    fun `the card names the book, then the chapter and its time left, by the author`() {
        // 9 s into chapter 6 (500 s .. 600 s): 91 s left reads "1m".
        val metadata = presented(chapters, positionMs = 509_000L)

        assertEquals("The Way of Kings", metadata.title?.toString())
        assertEquals("Bridge Four · 1m left", metadata.artist?.toString())
        assertEquals("Brandon Sanderson", metadata.albumTitle?.toString())
        assertEquals(6, metadata.trackNumber)
        assertEquals(75, metadata.totalTrackCount)
    }

    @Test
    fun `a chapterless book reads Whole book, with no track number`() {
        val metadata = presented(emptyList(), positionMs = 0L)

        assertEquals("The Way of Kings", metadata.title?.toString())
        assertEquals("Whole book · 2h 30m left", metadata.artist?.toString())
        assertEquals(null, metadata.trackNumber)
    }
}
