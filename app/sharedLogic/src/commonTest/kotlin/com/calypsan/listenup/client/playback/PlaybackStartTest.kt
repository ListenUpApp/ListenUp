package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlaybackStartTest :
    FunSpec({
        val hour = 3_600_000L

        test("a back skip lands that far before wherever it is replayed from") {
            landingMs(5 * hour, listOf(PlaybackMove.Skip(-10_000)), threeChapters, 6 * hour) shouldBe 5 * hour - 10_000
        }

        test("moves clamp into the book at both ends, at every step") {
            landingMs(5_000, listOf(PlaybackMove.Skip(-10_000)), threeChapters, 300_000) shouldBe 0L
            landingMs(295_000, listOf(PlaybackMove.Skip(30_000)), threeChapters, 300_000) shouldBe 299_999L
            landingMs(5_000, listOf(PlaybackMove.Skip(-10_000), PlaybackMove.Skip(20_000)), threeChapters, 300_000) shouldBe
                20_000L
        }

        test("chapter steps are measured from where they are replayed, with the 3 s restart rule") {
            landingMs(150_000, listOf(PlaybackMove.NextChapter), threeChapters, 300_000) shouldBe 200_000L
            landingMs(250_000, listOf(PlaybackMove.NextChapter), threeChapters, 300_000) shouldBe 250_000L
            landingMs(150_000, listOf(PlaybackMove.PreviousChapter), threeChapters, 300_000) shouldBe 100_000L
            // A chapterless book has no previous, so a carried previous moves nothing.
            landingMs(150_000, listOf(PlaybackMove.PreviousChapter), emptyList(), 300_000) shouldBe 150_000L
            landingMs(
                150_000,
                listOf(PlaybackMove.PreviousChapter, PlaybackMove.PreviousChapter),
                threeChapters,
                300_000,
            ) shouldBe 0L
        }

        test("a move on a saved-position start is carried; on a picked place it is applied at once") {
            PlaybackStart.Resume.then(PlaybackMove.NextChapter, threeChapters, 300_000) shouldBe
                PlaybackStart.Moved(listOf(PlaybackMove.NextChapter))
            PlaybackStart.Moved(listOf(PlaybackMove.Skip(-10_000))).then(PlaybackMove.NextChapter, threeChapters, 300_000) shouldBe
                PlaybackStart.Moved(listOf(PlaybackMove.Skip(-10_000), PlaybackMove.NextChapter))
            PlaybackStart.At(100_000).then(PlaybackMove.Skip(30_000), threeChapters, 300_000) shouldBe PlaybackStart.At(130_000)
        }
    })
