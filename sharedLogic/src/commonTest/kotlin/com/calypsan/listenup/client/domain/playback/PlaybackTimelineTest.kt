package com.calypsan.listenup.client.domain.playback

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlaybackTimelineTest :
    FunSpec({
        test("build assembles segments with given signed URLs and cumulative offsets") {
            val timeline =
                PlaybackTimeline.build(
                    bookId = BookId("b1"),
                    files =
                        listOf(
                            TimelineFileInput(
                                "af1",
                                "01.mp3",
                                "mp3",
                                1000L,
                                10L,
                                localPath = null,
                                streamingUrl = "https://s/api/v1/audio/b1/af1?sig=x",
                            ),
                            TimelineFileInput(
                                "af2",
                                "02.mp3",
                                "mp3",
                                2000L,
                                20L,
                                localPath = "/data/af2.mp3",
                                streamingUrl = "",
                            ),
                        ),
                )
            timeline.totalDurationMs shouldBe 3000L
            timeline.files[0].streamingUrl shouldBe "https://s/api/v1/audio/b1/af1?sig=x"
            timeline.files[0].startOffsetMs shouldBe 0L
            timeline.files[0].playbackUri shouldBe "https://s/api/v1/audio/b1/af1?sig=x"
            timeline.files[1].startOffsetMs shouldBe 1000L
            timeline.files[1].playbackUri shouldBe "file:///data/af2.mp3"
            timeline.isFullyDownloaded shouldBe false
        }
    })
