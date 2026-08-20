package com.calypsan.listenup.client.domain.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TimelineHlsCarriageTest :
    FunSpec({
        test("a file's HLS url survives into its timeline segment") {
            val timeline =
                PlaybackTimeline.build(
                    bookId = BookId("book-1"),
                    files =
                        listOf(
                            TimelineFileInput(
                                audioFileId = "af-1",
                                filename = "01.m4b",
                                format = "m4b",
                                durationMs = 1_000,
                                size = 10,
                                localPath = null,
                                streamingUrl = "https://host/api/v1/audio/book-1/af-1?sig=x",
                                hlsUrl = "https://host/api/v1/hls/book-1/af-1/master.m3u8?sig=x",
                            ),
                        ),
                )

            timeline.files.single().hlsUrl shouldBe "https://host/api/v1/hls/book-1/af-1/master.m3u8?sig=x"
        }

        test("playbackUri is unchanged by HLS — the direct url stays the never-stranded fallback") {
            val timeline =
                PlaybackTimeline.build(
                    bookId = BookId("book-1"),
                    files =
                        listOf(
                            TimelineFileInput(
                                audioFileId = "af-1",
                                filename = "01.m4b",
                                format = "m4b",
                                durationMs = 1_000,
                                size = 10,
                                localPath = null,
                                streamingUrl = "https://host/direct",
                                hlsUrl = "https://host/hls.m3u8",
                            ),
                        ),
                )

            timeline.files.single().playbackUri shouldBe "https://host/direct"
        }
    })
