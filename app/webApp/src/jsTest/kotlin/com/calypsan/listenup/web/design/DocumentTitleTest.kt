package com.calypsan.listenup.web.design

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.web.features.nowplaying.playingTitleFor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The tab's one title (spec §5.7): the page's name, or what is playing while it plays. */
class DocumentTitleTest :
    FunSpec({
        fun wayOfKings(
            phase: PlayerPhase,
            current: Int = 1,
            secondTitle: String = "Bridge Four",
        ): PlayerUiState.Loaded =
            loadedPlayerState(
                title = "The Way of Kings",
                phase = phase,
                chapters =
                    listOf(
                        PlayerChapterRow(0, 1, "Prelude", 0, 300_000, isCurrent = false),
                        PlayerChapterRow(1, 2, secondTitle, 300_000, 300_000, isCurrent = false),
                    ),
                currentChapterIndex = current,
            )

        test("the page's own title when nothing plays") {
            val title = DocumentTitle()
            title.page = "Library"
            title.title shouldBe "Library · ListenUp"
            title.page = null
            title.title shouldBe "ListenUp"
        }

        test("while a book plays, every page's tab names the chapter and the book") {
            val title = DocumentTitle()
            title.page = "Library"
            title.playing = playingTitleFor(wayOfKings(PlayerPhase.Playing))
            title.title shouldBe "Bridge Four · The Way of Kings · ListenUp"
            title.page = "Settings"
            title.title shouldBe "Bridge Four · The Way of Kings · ListenUp"
        }

        test("a chapter change names the new chapter; an untitled one by its number") {
            playingTitleFor(wayOfKings(PlayerPhase.Playing, current = 0)) shouldBe "Prelude · The Way of Kings"
            playingTitleFor(wayOfKings(PlayerPhase.Playing, secondTitle = " ")) shouldBe "Chapter 2 · The Way of Kings"
        }

        test("paused, finished or closed, the tab goes back to the page's name") {
            playingTitleFor(wayOfKings(PlayerPhase.Paused)) shouldBe null
            playingTitleFor(wayOfKings(PlayerPhase.Finished)) shouldBe null
            playingTitleFor(PlayerUiState.NothingLoaded) shouldBe null
            playingTitleFor(wayOfKings(PlayerPhase.SleepFading)) shouldBe "Bridge Four · The Way of Kings"
        }

        test("documentTitleFor keeps its cases") {
            documentTitleFor("Library") shouldBe "Library · ListenUp"
            documentTitleFor(" ") shouldBe "ListenUp"
        }
    })
