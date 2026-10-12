package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.PlaybackManager.PrepareResult
import com.calypsan.listenup.core.BookId

/** A one-file timeline for [bookId], [durationMs] long, streamed (no local path). */
fun timelineFor(
    bookId: String,
    durationMs: Long,
): PlaybackTimeline =
    PlaybackTimeline(
        bookId = BookId(bookId),
        totalDurationMs = durationMs,
        files =
            listOf(
                PlaybackTimeline.FileSegment(
                    audioFileId = "af-stub",
                    filename = "stub.m4b",
                    format = "m4b",
                    startOffsetMs = 0L,
                    durationMs = durationMs,
                    size = 1_000_000L,
                    streamingUrl = "https://example.test/stub.m4b",
                    localPath = null,
                    mediaItemIndex = 0,
                ),
            ),
    )

/**
 * A successful [PrepareResult] for [bookId]: one streamed file, resuming at 0 at 1.0×, no boost.
 * [PrepareResult] carries only a chapter count, so [chapters] sets `totalChapters` (at least 1).
 */
fun prepareResultFor(
    bookId: String,
    durationMs: Long = 600_000,
    chapters: List<Chapter> = emptyList(),
): PrepareResult =
    PrepareResult(
        timeline = timelineFor(bookId, durationMs),
        bookTitle = "Stub Book",
        bookAuthor = "Stub Author",
        seriesName = null,
        coverPath = null,
        totalChapters = chapters.size.coerceAtLeast(1),
        resumePositionMs = 0L,
        resumeSpeed = 1.0f,
        resumeBoostDb = 0f,
        measuredGainDb = null,
        normalizationGainDb = null,
    )
