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

        // --------------------------------------------------------------------
        // Multi-file coordinate conversion (F2 regression guards).
        //
        // Android builds one Media3 media item per file, so player coordinates are
        // FILE-relative (mediaItemIndex + positionInFile). The book position is the
        // sum of prior file durations + the in-file offset. These pin both directions
        // of the conversion for a 10-file book so a regression that feeds file-relative
        // positions into persistence/skip math is caught here first.
        // --------------------------------------------------------------------

        // 10 files × 1 hour each.
        fun tenHourBook(): PlaybackTimeline =
            PlaybackTimeline.build(
                bookId = BookId("b-multi"),
                files =
                    (0 until 10).map { i ->
                        TimelineFileInput(
                            audioFileId = "af$i",
                            filename = "file$i.mp3",
                            format = "mp3",
                            durationMs = 3_600_000L,
                            size = 1L,
                            localPath = null,
                            streamingUrl = "https://s/af$i",
                        )
                    },
            )

        test("toBookPosition maps file 7 at 12min to 7h12m book position") {
            val timeline = tenHourBook()
            // 7 × 3_600_000 + 720_000 = 25_920_000
            timeline.toBookPosition(mediaItemIndex = 7, positionInFileMs = 720_000L) shouldBe 25_920_000L
        }

        test("toBookPosition of file 0 offset equals the raw offset (single/first file)") {
            val timeline = tenHourBook()
            timeline.toBookPosition(mediaItemIndex = 0, positionInFileMs = 720_000L) shouldBe 720_000L
        }

        test("resolve of a +30s skip from book position 9h30m lands in file 9 at 30m30s") {
            val timeline = tenHourBook()
            // 9h30m = 34_200_000; + 30s = 34_230_000
            val resolved = timeline.resolve(34_230_000L)
            resolved.mediaItemIndex shouldBe 9
            resolved.positionInFileMs shouldBe 1_830_000L // 30m30s into file 9
        }

        test("resolve of a reverse skip across a file boundary lands in the previous file") {
            val timeline = tenHourBook()
            // 10s into file 5 = 18_010_000; skip back 30s → 17_980_000 (still inside file 4)
            val resolved = timeline.resolve(17_980_000L)
            resolved.mediaItemIndex shouldBe 4
            resolved.positionInFileMs shouldBe 3_580_000L // 59m40s into file 4
        }

        test("toBookPosition then resolve round-trips a mid-book position") {
            val timeline = tenHourBook()
            val bookPos = timeline.toBookPosition(mediaItemIndex = 3, positionInFileMs = 123_456L)
            val resolved = timeline.resolve(bookPos)
            resolved.mediaItemIndex shouldBe 3
            resolved.positionInFileMs shouldBe 123_456L
        }
    })
