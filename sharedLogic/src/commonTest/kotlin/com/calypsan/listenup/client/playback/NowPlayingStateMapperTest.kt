package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowPlayingStateMapperTest {
    private val emptyDynamics =
        PlaybackDynamics(
            isPlaying = false,
            isBuffering = false,
            currentPositionMs = 0L,
            totalDurationMs = 0L,
            playbackSpeed = 1.0f,
        )

    private val emptyMetadata =
        SurfaceMetadata(
            currentChapter = null,
            prepareProgress = null,
            error = null,
            defaultPlaybackSpeed = 1.0f,
        )

    private fun sampleBook(
        id: String = "book-1",
        title: String = "Sample Book",
        duration: Long = 100_000L,
    ): BookListItem =
        BookListItem(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = title,
            authors = listOf(BookContributor(id = "author-1", name = "Test Author", roles = listOf("Author"))),
            narrators = listOf(BookContributor(id = "narrator-1", name = "Test Narrator", roles = listOf("Narrator"))),
            duration = duration,
            coverPath = "/covers/sample.jpg",
            coverBlurHash = "L6PZfSi_.AyE_3t7t7R**0o#DgR4",
            addedAt = Timestamp(epochMillis = 1_704_067_200_000L),
            updatedAt = Timestamp(epochMillis = 1_704_067_200_000L),
        )

    @Test
    fun `mapToNowPlayingState returns Idle when book is null and no error`() {
        val result = mapToNowPlayingState(book = null, dynamics = emptyDynamics, metadata = emptyMetadata)
        assertEquals(NowPlayingState.Idle, result)
    }

    @Test
    fun `mapToNowPlayingState returns Error with null bookId when book is null and error present`() {
        val metadata =
            emptyMetadata.copy(
                error =
                    PlaybackManager.PlaybackErrorUiState(
                        message = "Network failure",
                        isRecoverable = true,
                        timestampMs = 1_000L,
                    ),
            )
        val result = mapToNowPlayingState(book = null, dynamics = emptyDynamics, metadata = metadata)
        assertIs<NowPlayingState.Error>(result)
        assertNull(result.bookId)
        assertNull(result.title)
        assertEquals("Network failure", result.message)
        assertTrue(result.isRecoverable)
    }

    @Test
    fun `mapToNowPlayingState returns Error with bookId when both book and error present`() {
        val book = sampleBook()
        val metadata =
            emptyMetadata.copy(
                error =
                    PlaybackManager.PlaybackErrorUiState(
                        message = "Codec error",
                        isRecoverable = false,
                        timestampMs = 1_000L,
                    ),
            )
        val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = metadata)
        assertIs<NowPlayingState.Error>(result)
        assertEquals("book-1", result.bookId)
        assertEquals("Sample Book", result.title)
        assertEquals("Codec error", result.message)
        assertEquals(false, result.isRecoverable)
    }

    @Test
    fun `mapToNowPlayingState returns Preparing when book present and prepareProgress non-null`() {
        val book = sampleBook()
        val metadata =
            emptyMetadata.copy(
                prepareProgress =
                    PlaybackManager.PrepareProgress(
                        audioFileId = "file-1",
                        progress = 42,
                        message = "Transcoding...",
                    ),
            )
        val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = metadata)
        assertIs<NowPlayingState.Preparing>(result)
        assertEquals("book-1", result.bookId)
        assertEquals("Sample Book", result.title)
        assertEquals("Test Author", result.author)
        assertEquals("/covers/sample.jpg", result.coverPath)
        assertEquals("L6PZfSi_.AyE_3t7t7R**0o#DgR4", result.coverBlurHash)
        assertEquals(42, result.progress)
        assertEquals("Transcoding...", result.message)
    }

    @Test
    fun `mapToNowPlayingState returns Active for normal playback`() {
        val book = sampleBook(duration = 100_000L)
        val dynamics =
            emptyDynamics.copy(
                isPlaying = true,
                currentPositionMs = 50_000L,
                totalDurationMs = 100_000L,
                playbackSpeed = 1.5f,
            )
        val result = mapToNowPlayingState(book = book, dynamics = dynamics, metadata = emptyMetadata)
        assertIs<NowPlayingState.Active>(result)
        assertEquals("book-1", result.bookId)
        assertEquals(true, result.isPlaying)
        assertEquals(0.5f, result.bookProgress)
        assertEquals(50_000L, result.bookPositionMs)
        assertEquals(100_000L, result.bookDurationMs)
        assertEquals(1.5f, result.playbackSpeed)
    }

    @Test
    fun `mapToNowPlayingState Active chapterLabel is empty when totalChapters is 0`() {
        val book = sampleBook()
        val result = mapToNowPlayingState(book = book, dynamics = emptyDynamics, metadata = emptyMetadata)
        assertIs<NowPlayingState.Active>(result)
        assertEquals(0, result.totalChapters)
        assertEquals("", result.chapterLabel)
    }

    @Test
    fun `mapToNowPlayingState Active bookProgress clamps to 0_1f range`() {
        val book = sampleBook(duration = 100_000L)
        // Position past duration — progress should clamp to 1.0f
        val dynamics = emptyDynamics.copy(currentPositionMs = 200_000L, totalDurationMs = 100_000L)
        val result = mapToNowPlayingState(book = book, dynamics = dynamics, metadata = emptyMetadata)
        assertIs<NowPlayingState.Active>(result)
        assertEquals(1.0f, result.bookProgress)
    }

    @Test
    fun `mapToNowPlayingState Active chapterPositionMs cannot be negative`() {
        val book = sampleBook()
        // Chapter starts at 60_000 but we are at position 30_000 (before chapter start)
        val chapter =
            PlaybackManager.ChapterInfo(
                index = 1,
                title = "Chapter 2",
                startMs = 60_000L,
                endMs = 90_000L,
                remainingMs = 30_000L,
                totalChapters = 3,
                isGenericTitle = false,
            )
        val dynamics = emptyDynamics.copy(currentPositionMs = 30_000L, totalDurationMs = 100_000L)
        val metadata = emptyMetadata.copy(currentChapter = chapter)
        val result = mapToNowPlayingState(book = book, dynamics = dynamics, metadata = metadata)
        assertIs<NowPlayingState.Active>(result)
        assertEquals(0L, result.chapterPositionMs) // coerceAtLeast(0L) — cannot be negative
        assertEquals(30_000L, result.chapterDurationMs) // endMs - startMs
        assertEquals(3, result.totalChapters) // pulled from chapter.totalChapters
    }
}
