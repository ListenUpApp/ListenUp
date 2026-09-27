package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * [playingChapterIds] is what each row's "Now" badge is derived from. It is a set of ids rather
 * than a position so the rows only hear about a tick when the answer changes — the playhead moves
 * many times a second, the playing chapter a few times an hour.
 */
class PlayingChapterIdsTest :
    FunSpec({
        val chapters =
            listOf(
                Chapter(id = "a", title = "A", duration = 10_000L, startTime = 0L),
                Chapter(id = "b", title = "B", duration = 10_000L, startTime = 10_000L),
            ).numbered()

        test("no playhead means no chapter is playing") {
            playingChapterIds(chapters, null).shouldBeEmpty()
        }

        test("a chapter's start is inside it and its end is not") {
            playingChapterIds(chapters, 0L) shouldBe setOf("a")
            playingChapterIds(chapters, 9_999L) shouldBe setOf("a")
            playingChapterIds(chapters, 10_000L) shouldBe setOf("b")
        }

        test("two ticks inside one chapter give the same answer") {
            playingChapterIds(chapters, 1_000L) shouldBe playingChapterIds(chapters, 2_000L)
        }

        test("past the last chapter nothing is playing") {
            playingChapterIds(chapters, 20_000L).shouldBeEmpty()
        }
    })
