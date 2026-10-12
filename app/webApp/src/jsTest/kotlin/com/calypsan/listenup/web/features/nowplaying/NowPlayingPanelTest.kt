package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

private const val BOOK_MS = FIXTURE_BOOK_MS

/** The Way of Kings as the shared model hands it over: a known author, one series. */
@Suppress("LongParameterList") // one knob per thing a spec varies
private fun kings(
    bookId: String = "b1",
    coverHash: String? = "abc123",
    authors: List<PlayerContributor> = listOf(PlayerContributor("c1", "Brandon Sanderson", 1)),
    series: List<PlayerSeriesRef> = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
    phase: PlayerPhase = PlayerPhase.Paused,
    chapters: List<PlayerChapterRow> = emptyList(),
    currentChapterIndex: Int? = null,
    boostDb: Float = 0f,
): PlayerUiState.Loaded =
    loadedPlayerState(
        title = "The Way of Kings",
        bookId = bookId,
        phase = phase,
        authors = authors,
        series = series,
        coverHash = coverHash,
        durationMs = BOOK_MS,
        chapters = chapters,
        currentChapterIndex = currentChapterIndex,
        boostDb = boostDb,
    )

/**
 * The expanded player: the book the bar has no room to describe, and the places you can go from it.
 *
 * The docked bar can hold a title, a playhead and a row of round controls. Everything else about
 * the thing you are listening to — its cover, who wrote it, which series it belongs to, which
 * chapter is playing — had nowhere on web to be until this panel. What these pin: the panel is
 * reachable at any width, it states only what is actually known, every destination carries a real
 * id, chapter stepping stops at both ends of the book, and opening one of the four pickers from
 * here does not throw away the panel underneath it.
 */
class NowPlayingPanelTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        /** Just the bar, with no panel opened — enough for the controls it owns itself. */
        fun plainBar(state: PlayerUiState.Loaded): HTMLElement =
            mounts.mount {
                TransportBar(
                    state = state,
                    progress = playerProgress(),
                    onPrimary = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onSetSpeed = {},
                )
            }

        /**
         * Mounts the real bar, opens the expanded player through the handle a listener would use,
         * and hands back the host.
         *
         * Driven through [TransportBar] rather than by rendering [NowPlayingPanel] directly: the
         * panel is only worth anything if it can be reached, and the reaching is the half most
         * likely to break — the bar hides half its controls under 760px, and a gesture hung off one
         * of those would be gone on a phone without a single assertion noticing.
         */
        suspend fun openPanel(
            state: PlayerUiState.Loaded = kings(),
            onPrimary: () -> Unit = {},
            onPreviousChapter: () -> Unit = {},
            onNextChapter: () -> Unit = {},
            onOpenBook: (String) -> Unit = {},
            onOpenSeries: (String) -> Unit = {},
            onOpenContributor: (String) -> Unit = {},
            onCloseBook: () -> Unit = {},
        ): HTMLElement {
            val host =
                mounts.mount {
                    TransportBar(
                        state = state,
                        progress = playerProgress(durationMs = BOOK_MS),
                        onPrimary = onPrimary,
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onOpenBook = onOpenBook,
                        onOpenSeries = onOpenSeries,
                        onOpenContributor = onOpenContributor,
                        onCloseBook = onCloseBook,
                    )
                }
            (host.querySelector(".tport-expand") as HTMLElement).click()
            awaitFrame()
            return host
        }

        test("the handle opens the player") {
            val host = openPanel()

            host.querySelector(".np-dlg").shouldNotBeNull()
            (host.querySelector(".np-t") as HTMLElement).textContent shouldBe "The Way of Kings"
        }

        test("the panel's seek says where you are against the whole book") {
            val host = openPanel()

            (host.querySelector(".np-scrub") as HTMLElement).getAttribute("aria-valuetext") shouldBe
                "${formatElapsed(0)} of ${formatElapsed(BOOK_MS)}"
        }

        // The title and the skips are the parts of the bar a layout is most tempted to drop (the
        // skips do step aside on a phone). A gesture hung off either would leave the expanded
        // player one media query away from unreachable.
        test("the handle is not the title, which a narrow screen hides") {
            val host = openPanel()

            val handle = host.querySelector(".tport-expand") as HTMLElement
            handle.classList.contains("tport-t") shouldBe false
            handle.classList.contains("tport-skip") shouldBe false
        }

        test("the cover carries the book's hash, so a re-cover is not served from a year-old cache") {
            val host = openPanel(kings(bookId = "b7", coverHash = "deadbeef"))

            val cover = host.querySelector(".np-body img") as HTMLImageElement
            cover.src shouldContain "/api/v1/books/b7/cover"
            cover.src shouldContain "v=deadbeef"
        }

        test("every author is named, and opens that contributor by id") {
            val opened = mutableListOf<String>()
            val host =
                openPanel(
                    kings(
                        authors =
                            listOf(
                                PlayerContributor("c1", "Robert Jordan", 1),
                                PlayerContributor("c2", "Brandon Sanderson", 1),
                            ),
                    ),
                    onOpenContributor = { opened += it },
                )

            val names = host.querySelectorAll(".np-by-name")
            names.length shouldBe 2
            (names.item(1) as HTMLElement).click()

            opened shouldBe listOf("c2")
        }

        test("a book with no author renders no byline rather than an empty line") {
            val host = openPanel(kings(authors = emptyList()))

            host.querySelector(".np-by") shouldBe null
        }

        test("the series chip carries this book's position and opens that series") {
            val opened = mutableListOf<String>()
            val host =
                openPanel(
                    kings(series = listOf(PlayerSeriesRef("s-cosmere", "The Cosmere", "7"))),
                    onOpenSeries = { opened += it },
                )

            val chip = host.querySelector(".np-series-chip") as HTMLElement
            chip.textContent.orEmpty() shouldContain "The Cosmere"
            (host.querySelector(".np-series-seq") as HTMLElement).textContent shouldBe "#7"
            chip.click()

            opened shouldBe listOf("s-cosmere")
        }

        test("a book in two series shows both, in the book's order, each opening its own") {
            // The shared model carries every membership; showing only the first would drop the Cosmere.
            val opened = mutableListOf<String>()
            val host =
                openPanel(
                    kings(
                        series =
                            listOf(
                                PlayerSeriesRef("s1", "The Stormlight Archive", "1"),
                                PlayerSeriesRef("s-cosmere", "The Cosmere", "7"),
                            ),
                    ),
                    onOpenSeries = { opened += it },
                )

            val chips = host.querySelectorAll(".np-series-chip")
            chips.length shouldBe 2
            (chips.item(0) as HTMLElement).textContent.orEmpty() shouldContain "The Stormlight Archive"
            (chips.item(1) as HTMLElement).textContent.orEmpty() shouldContain "The Cosmere"
            (chips.item(1) as HTMLElement).click()

            opened shouldBe listOf("s-cosmere")
        }

        test("a standalone book renders no series row") {
            val host = openPanel(kings(series = emptyList()))

            host.querySelector(".np-series") shouldBe null
        }

        test("the chapter line names where you are and how far in") {
            val host = openPanel(kings(chapters = chapterRows(76), currentChapterIndex = 11))

            (host.querySelector(".np-ch-n") as HTMLElement).textContent shouldBe "Chapter 12 of 76"
            (host.querySelector(".np-ch-t") as HTMLElement).textContent shouldBe "Chapter 12"
        }

        // "Chapter 1 of 1" states something about a structure the book does not have.
        test("a book with no marks renders no chapter line") {
            val host = openPanel(kings(chapters = emptyList()))

            host.querySelector(".np-ch") shouldBe null
        }

        test("chapter steps ask the shared model for the previous and the next chapter") {
            // Where they land (§3.3's three-second rule) is the shared model's, not this panel's.
            var previous = 0
            var next = 0
            val host =
                openPanel(
                    kings(chapters = chapterRows(5), currentChapterIndex = 2),
                    onPreviousChapter = { previous++ },
                    onNextChapter = { next++ },
                )

            val steps = host.querySelectorAll(".np-chstep")
            (steps.item(0) as HTMLElement).click()
            (steps.item(1) as HTMLElement).click()

            previous shouldBe 1
            next shouldBe 1
        }

        // §3.3: in chapter one, Previous restarts it, so it is offered; only a chapterless book has nowhere to step.
        test("previous is offered in the first chapter, where it restarts it") {
            val host = openPanel(kings(chapters = chapterRows(5), currentChapterIndex = 0))

            val steps = host.querySelectorAll(".np-chstep")
            (steps.item(0) as HTMLElement).hasAttribute("disabled") shouldBe false
            (steps.item(1) as HTMLElement).hasAttribute("disabled") shouldBe false
        }

        // Genuinely `disabled`, not merely inert: it is the only version a screen reader also hears.
        test("next is disabled in the last chapter") {
            val host = openPanel(kings(chapters = chapterRows(5), currentChapterIndex = 4))

            val steps = host.querySelectorAll(".np-chstep")
            (steps.item(0) as HTMLElement).hasAttribute("disabled") shouldBe false
            (steps.item(1) as HTMLElement).hasAttribute("disabled") shouldBe true
        }

        test("Go to book opens the playing book") {
            val opened = mutableListOf<String>()
            val host = openPanel(kings(bookId = "b42"), onOpenBook = { opened += it })

            (host.querySelector(".np-goto") as HTMLElement).click()

            opened shouldBe listOf("b42")
        }

        test("Close book reports exactly one press and puts the player away") {
            // Android's player offers this from its overflow menu; without it a web listener who is
            // done with a book can only pause it, and the bar goes on naming it on every page.
            var closes = 0
            val host = openPanel(onCloseBook = { closes++ })

            val close = host.querySelector(".np-close-book") as HTMLElement
            close.textContent.orEmpty() shouldContain "Close book"
            close.click()
            awaitFrame()

            closes shouldBe 1
            host.querySelector(".np-dlg[open]") shouldBe null
        }

        test("the panel's play control reports a press and says which action it is") {
            var presses = 0
            val host = openPanel(kings(phase = PlayerPhase.Playing), onPrimary = { presses++ })

            val play = host.querySelector(".np-play") as HTMLElement
            play.getAttribute("aria-label") shouldBe "Pause"
            play.click()

            presses shouldBe 1
        }

        test("the boost chip reads the boost when there is one, and invites one when there is not") {
            val off = openPanel(kings(boostDb = 0f))
            val on = openPanel(kings(boostDb = 6f))

            off.textContent.orEmpty() shouldContain "Boost"
            off.textContent.orEmpty() shouldNotContain "+6 dB"
            on.textContent.orEmpty() shouldContain "+6 dB"
        }

        // Closing the panel to change the speed would lose the place the listener was looking at.
        test("opening a picker from the panel leaves the panel underneath it") {
            val host = openPanel()

            val chips = host.querySelectorAll(".np-chip")
            (chips.item(0) as HTMLElement).click()
            awaitFrame()

            host.querySelector(".speed-dlg").shouldNotBeNull()
            host.querySelector(".np-dlg").shouldNotBeNull()
        }

        test("a stalled player says so on the control, and in words") {
            // ⛔ `HtmlAudioPlayer` has published Buffering since it was written and nothing read it,
            // so a book that stalled showed a Pause icon and a frozen clock — indistinguishable
            // from the app being broken. The label matters as much as the spinner: a spinner is
            // invisible to a screen reader, and "Pause" while stalled lies about what the control
            // would do.
            val host = plainBar(kings(phase = PlayerPhase.Buffering))

            host.querySelector(".tport-wait").shouldNotBeNull()
            (host.querySelector(".tport-b") as HTMLElement).getAttribute("aria-label") shouldBe "Loading"
        }

        test("a player that is merely playing shows no spinner") {
            val host = plainBar(kings(phase = PlayerPhase.Playing))

            host.querySelector(".tport-wait") shouldBe null
            (host.querySelector(".tport-b") as HTMLElement).getAttribute("aria-label") shouldBe "Pause"
        }
    })
