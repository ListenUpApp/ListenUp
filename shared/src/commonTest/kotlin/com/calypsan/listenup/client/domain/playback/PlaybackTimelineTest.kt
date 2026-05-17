package com.calypsan.listenup.client.domain.playback

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.domain.model.AudioFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackTimelineTest {
    private val bookId = BookId("book-1")
    private val baseUrl = "https://server.example.com"

    private fun audioFile(
        id: String = "af-001",
        index: Int = 0,
        filename: String = "chapter1.mp3",
        format: String = "mp3",
        codec: String = "mp3",
        duration: Long = 1_800_000L,
        size: Long = 30_000_000L,
    ): AudioFile =
        AudioFile(
            id = id,
            index = index,
            filename = filename,
            format = format,
            codec = codec,
            duration = duration,
            size = size,
        )

    @Test
    fun `buildWithTranscodeSupport never calls prepareStream and uses direct URLs for non-local files`() =
        runTest {
            // Given - two files: first is local, second is not
            val files =
                listOf(
                    audioFile(id = "af-001", filename = "chapter1.mp3"),
                    audioFile(id = "af-002", filename = "chapter2.mp3"),
                )

            var prepareStreamCalled = false

            // When
            val timeline =
                PlaybackTimeline.buildWithTranscodeSupport(
                    bookId = bookId,
                    audioFiles = files,
                    baseUrl = baseUrl,
                    resolveLocalPath = { fileId ->
                        if (fileId == "af-001") "/downloads/chapter1.mp3" else null
                    },
                    prepareStream = { _, _ ->
                        prepareStreamCalled = true
                        StreamPrepareResult(streamUrl = "/api/v1/books/book-1/audio/af-002", ready = true)
                    },
                )

            // Then - prepareStream should never be called
            assertFalse(prepareStreamCalled, "prepareStream should not be called at all")

            // Non-local file should get direct URL pattern
            val nonLocalSegment = timeline.files[1]
            assertEquals(
                "$baseUrl/api/v1/books/${bookId.value}/audio/af-002",
                nonLocalSegment.streamingUrl,
            )
        }

    @Test
    fun `buildWithTranscodeSupport uses direct URLs for all non-local files`() =
        runTest {
            // Given - three non-local files
            val files =
                listOf(
                    audioFile(id = "af-001", filename = "chapter1.mp3"),
                    audioFile(id = "af-002", filename = "chapter2.mp3"),
                    audioFile(id = "af-003", filename = "chapter3.mp3"),
                )

            var prepareStreamCallCount = 0

            // When
            val timeline =
                PlaybackTimeline.buildWithTranscodeSupport(
                    bookId = bookId,
                    audioFiles = files,
                    baseUrl = baseUrl,
                    resolveLocalPath = { null }, // none are local
                    prepareStream = { _, _ ->
                        prepareStreamCallCount++
                        StreamPrepareResult(streamUrl = "ignored", ready = true)
                    },
                )

            // Then - prepareStream should never be called
            assertEquals(0, prepareStreamCallCount, "prepareStream should not be called")

            // All files should get the direct URL pattern (no ?variant=transcoded suffix)
            for (segment in timeline.files) {
                assertEquals(
                    "$baseUrl/api/v1/books/${bookId.value}/audio/${segment.audioFileId}",
                    segment.streamingUrl,
                )
            }
        }
}
