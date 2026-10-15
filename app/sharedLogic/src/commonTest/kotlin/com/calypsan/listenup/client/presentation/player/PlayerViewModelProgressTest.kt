package com.calypsan.listenup.client.presentation.player

import app.cash.turbine.test
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** §4.10: a position tick moves [PlayerProgress] and never [PlayerUiState]; the chapter label and rows agree. */
class PlayerViewModelProgressTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("a position tick re-emits progress and not uiState; a chapter change re-emits uiState") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.playBook(BookId("b1"), 10_000)
                    runCurrent()
                    rig.vm.uiState.test {
                        runCurrent() // starts the subscription's upstream; moves no virtual time
                        expectMostRecentItem()
                        rig.vm.progress.test {
                            runCurrent() // starts the subscription's upstream; moves no virtual time
                            expectMostRecentItem()
                            rig.s.engine.setPosition(20_000)
                            runCurrent()
                            awaitItem().chapterPositionMs shouldBe 20_000
                            rig.s.engine.setPosition(30_000)
                            runCurrent()
                            awaitItem().bookPositionMs shouldBe 30_000
                        }
                        expectNoEvents()
                        rig.s.engine.setPosition(110_000)
                        runCurrent()
                        awaitItem().shouldBeInstanceOf<PlayerUiState.Loaded>().chapter.index shouldBe 1
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("for every position the chapter label and the one current row agree") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.playBook(BookId("b1"), 0)
                    runCurrent()
                    rig.vm.uiState.test {
                        listOf(50_000L, 100_000L, 150_000L, 250_000L, 99_999L).forEach { ms ->
                            rig.s.engine.setPosition(ms)
                            runCurrent()
                            // The value, not the next emission: inside one chapter a tick does not re-emit.
                            val loaded =
                                rig.vm.uiState.value
                                    .shouldBeInstanceOf<PlayerUiState.Loaded>()
                            loaded.chapters.single { it.isCurrent }.index shouldBe loaded.chapter.index
                            loaded.chapter.startMs shouldBe ms - ms % 100_000
                        }
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("progress is chapter-scoped, and a chapterless book's chapter is the whole book") {
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1", chapters = emptyList(), durationMs = 1_000_000)
                    rig.vm.playBook(BookId("b1"), 250_000)
                    runCurrent()
                    rig.vm.progress.test {
                        runCurrent() // starts the subscription's upstream; moves no virtual time
                        val p = expectMostRecentItem()
                        p.chapterDurationMs shouldBe 1_000_000
                        p.chapterPositionMs shouldBe 250_000
                    }
                } finally {
                    rig.close()
                }
            }
        }

        test("a switch whose chapter arrives before its position never shows the chapter complete") {
            // The web bar showed the new book's chapter 3 full while it was 1% in: a frame paired the new book's
            // chapter range (20–30 s) with the old book's playhead (250 s), and the clamp made that "done".
            runTest {
                val rig = PlayerViewModelRig(this)
                try {
                    rig.s.withBook("b1")
                    rig.vm.playBook(BookId("b1"), 250_000)
                    runCurrent()
                    rig.vm.progress.test {
                        runCurrent() // starts the subscription's upstream; moves no virtual time
                        expectMostRecentItem().chapterPositionMs shouldBe 50_000
                        val engine = rig.s.engine
                        // The engine announces the next book's chapter and length first …
                        engine.durationMs.value = 30_000
                        engine.currentChapter.value =
                            PlaybackManager.ChapterInfo(
                                index = 2,
                                title = "Three",
                                startMs = 20_000,
                                endMs = 30_000,
                                remainingMs = 9_900,
                                totalChapters = 3,
                                isGenericTitle = false,
                            )
                        runCurrent()
                        // … and its playhead a moment later.
                        engine.positionMs.value = 20_100
                        runCurrent()
                        val frames =
                            cancelAndConsumeRemainingEvents()
                                .filterIsInstance<app.cash.turbine.Event.Item<PlayerProgress>>()
                                .map { it.value }
                        frames.none { it.chapterFraction == 1f } shouldBe true
                        frames.last().chapterPositionMs shouldBe 100
                        frames.last().chapterDurationMs shouldBe 10_000
                    }
                } finally {
                    rig.close()
                }
            }
        }
    })
