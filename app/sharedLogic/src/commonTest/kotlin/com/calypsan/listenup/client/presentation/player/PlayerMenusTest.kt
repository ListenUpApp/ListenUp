package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlayerMenusTest :
    FunSpec({
        test("a book in a series with a PDF and a boost: every row, in order, Close book apart") {
            PlayerMenus.moreMenu(
                loaded(series = true, pdf = "doc-1", boostDb = 6f),
                includesSeries = true,
                includesShortcuts = false,
            ) shouldBe
                listOf(
                    PlayerMenuGroup(
                        listOf(
                            PlayerMenuItem.GoToBook,
                            PlayerMenuItem.GoToSeries("s1"),
                            PlayerMenuItem.OpenPdf("doc-1"),
                            PlayerMenuItem.VolumeBoost(6f),
                        ),
                    ),
                    PlayerMenuGroup(listOf(PlayerMenuItem.CloseBook), isDestructive = true),
                )
        }

        test("no series, no PDF: Go to book and Volume boost, then Close book") {
            PlayerMenus.moreMenu(
                loaded(series = false, pdf = null, boostDb = 0f),
                includesSeries = true,
                includesShortcuts = false,
            ) shouldBe
                listOf(
                    PlayerMenuGroup(listOf(PlayerMenuItem.GoToBook, PlayerMenuItem.VolumeBoost(0f))),
                    PlayerMenuGroup(listOf(PlayerMenuItem.CloseBook), isDestructive = true),
                )
        }

        test("a wide layout that shows the series link leaves Go to series out") {
            PlayerMenus
                .moreMenu(loaded(series = true, pdf = null, boostDb = 0f), includesSeries = false, includesShortcuts = false)
                .first()
                .items shouldBe listOf(PlayerMenuItem.GoToBook, PlayerMenuItem.VolumeBoost(0f))
        }

        test("the web asks for Keyboard shortcuts: last of the first group, Close book still apart") {
            PlayerMenus.moreMenu(
                loaded(series = false, pdf = null, boostDb = 0f),
                includesSeries = true,
                includesShortcuts = true,
            ) shouldBe
                listOf(
                    PlayerMenuGroup(
                        listOf(
                            PlayerMenuItem.GoToBook,
                            PlayerMenuItem.VolumeBoost(0f),
                            PlayerMenuItem.KeyboardShortcuts,
                        ),
                    ),
                    PlayerMenuGroup(listOf(PlayerMenuItem.CloseBook), isDestructive = true),
                )
        }

        test("a platform without app shortcuts never sees the row") {
            PlayerMenus
                .moreMenu(loaded(series = true, pdf = "doc-1", boostDb = 6f), includesSeries = true, includesShortcuts = false)
                .flatMap { it.items }
                .contains(PlayerMenuItem.KeyboardShortcuts) shouldBe false
        }

        test("Go to series names the first series, the one the phone shows") {
            PlayerMenus
                .moreMenu(loaded(series = true, pdf = null, boostDb = 0f), includesSeries = true, includesShortcuts = false)
                .first()
                .items[1] shouldBe PlayerMenuItem.GoToSeries("s1")
        }
    })

private fun loaded(
    series: Boolean,
    pdf: String?,
    boostDb: Float,
): PlayerUiState.Loaded =
    PlayerUiState.Loaded(
        book =
            PlayerBook(
                "b1",
                "The Way of Kings",
                emptyList(),
                emptyList(),
                null,
                null,
                if (series) {
                    listOf(
                        PlayerSeriesRef("s1", "The Stormlight Archive", "1"),
                        PlayerSeriesRef("s9", "Cosmere", null),
                    )
                } else {
                    emptyList()
                },
                163_800_000,
                pdf,
            ),
        phase = PlayerPhase.Playing,
        chapter = PlayerChapter(5, 6, 75, "Bridge Four", isGenericTitle = false, isWholeBook = false, startMs = 0, durationMs = 1_235_000),
        chapters = emptyList(),
        source = AudioSource.Streaming,
        settings = PlayerSettings(1f, 1f, boostDb, 0f, 10, 30),
        sleepTimer = SleepTimerState.Inactive,
        primary = PrimaryControl.Pause,
        showsSkipBack = true,
        canSkipToNextChapter = true,
        finished = null,
    )
