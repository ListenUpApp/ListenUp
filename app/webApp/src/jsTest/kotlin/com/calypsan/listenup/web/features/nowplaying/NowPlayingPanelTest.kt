package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

private const val BOOK_MS = 600_000L

private const val CHAPTER_SPACING_MS = 1_000L

private val hosts = mutableListOf<HTMLElement>()

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) { content() }
    return host
}

private fun book(
    bookId: String = "b1",
    coverHash: String? = "abc123",
    authors: List<PlayerLink> = listOf(PlayerLink("c1", "Brandon Sanderson")),
    narrators: String = "Michael Kramer",
    series: List<PlayerSeriesLink> = listOf(PlayerSeriesLink("s1", "The Stormlight Archive", "1")),
) = NowPlayingBook(bookId, coverHash, authors, narrators, series)

private fun playing(isPlaying: Boolean): TransportState =
    TransportState(
        title = "The Way of Kings",
        isPlaying = isPlaying,
        positionMs = 0,
        durationMs = BOOK_MS,
    )

private fun chapters(count: Int): List<TransportChapter> =
    (0 until count).map {
        TransportChapter(title = "Chapter ${it + 1}", startMs = it * CHAPTER_SPACING_MS)
    }

/**
 * Mounts the real bar, opens the expanded player through the handle a listener would use, and
 * hands back the host.
 *
 * Driven through [TransportBar] rather than by rendering [NowPlayingPanel] directly: the panel is
 * only worth anything if it can be reached, and the reaching is the half most likely to break —
 * the bar hides half its controls under 760px, and a gesture hung off one of those would be gone
 * on a phone without a single assertion noticing.
 */
private suspend fun openPanel(
    state: TransportState = playing(isPlaying = false),
    nowPlaying: NowPlayingBook? = book(),
    chapterList: List<TransportChapter> = emptyList(),
    currentChapterIndex: Int? = null,
    sleepTimer: SleepTimerState = SleepTimerState.Inactive,
    volumeBoostDb: Float = 0f,
    onPlayPause: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSeekToChapter: (Int) -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onOpenContributor: (String) -> Unit = {},
): HTMLElement {
    val host =
        mount {
            TransportBar(
                state = state,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onSkipBack = {},
                onSkipForward = {},
                onSetSpeed = {},
                chapters = chapterList,
                currentChapterIndex = currentChapterIndex,
                onSeekToChapter = onSeekToChapter,
                sleepTimer = sleepTimer,
                volumeBoostDb = volumeBoostDb,
                nowPlaying = nowPlaying,
                onOpenBook = onOpenBook,
                onOpenSeries = onOpenSeries,
                onOpenContributor = onOpenContributor,
            )
        }
    (host.querySelector(".tport-expand") as HTMLElement).click()
    awaitFrame()
    return host
}

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

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the handle opens the player") {
            val host = openPanel()

            host.querySelector(".np-dlg").shouldNotBeNull()
            (host.querySelector(".np-t") as HTMLElement).textContent shouldBe "The Way of Kings"
        }

        // `.tport-t` is display:none under 760px. A gesture hung off the title would leave the
        // expanded player unreachable on exactly the screen whose bar shows least.
        test("the handle is not the title, which a narrow screen hides") {
            val host = openPanel()

            val handle = host.querySelector(".tport-expand") as HTMLElement
            handle.classList.contains("tport-t") shouldBe false
            handle.classList.contains("tport-skip") shouldBe false
        }

        test("the cover carries the book's hash, so a re-cover is not served from a year-old cache") {
            val host = openPanel(nowPlaying = book(bookId = "b7", coverHash = "deadbeef"))

            val cover = host.querySelector(".np-body img") as HTMLImageElement
            cover.src shouldContain "/api/v1/books/b7/cover"
            cover.src shouldContain "v=deadbeef"
        }

        // [state] is the player's truth and [book] is Room's; a book playing before the mirror has
        // caught up must still show what IS known rather than nothing.
        test("a book the mirror has not seen still opens, with no links and no cover") {
            val host = openPanel(nowPlaying = null)

            (host.querySelector(".np-t") as HTMLElement).textContent shouldBe "The Way of Kings"
            host.querySelector(".np-by") shouldBe null
            host.querySelector(".np-series") shouldBe null
            host.querySelector(".np-goto") shouldBe null
        }

        test("every author is named, and opens that contributor by id") {
            val opened = mutableListOf<String>()
            val host =
                openPanel(
                    nowPlaying =
                        book(
                            authors = listOf(PlayerLink("c1", "Robert Jordan"), PlayerLink("c2", "Brandon Sanderson")),
                        ),
                    onOpenContributor = { opened += it },
                )

            val names = host.querySelectorAll(".np-by-name")
            names.length shouldBe 2
            (names.item(1) as HTMLElement).click()

            opened shouldBe listOf("c2")
        }

        test("a book with no author renders no byline rather than an empty line") {
            val host = openPanel(nowPlaying = book(authors = emptyList()))

            host.querySelector(".np-by") shouldBe null
        }

        test("the series chip carries this book's position and opens that series") {
            val opened = mutableListOf<String>()
            val host =
                openPanel(
                    nowPlaying = book(series = listOf(PlayerSeriesLink("s-cosmere", "The Cosmere", "7"))),
                    onOpenSeries = { opened += it },
                )

            val chip = host.querySelector(".np-series-chip") as HTMLElement
            chip.textContent.orEmpty() shouldContain "The Cosmere"
            (host.querySelector(".np-series-seq") as HTMLElement).textContent shouldBe "#7"
            chip.click()

            opened shouldBe listOf("s-cosmere")
        }

        test("a standalone book renders no series row") {
            val host = openPanel(nowPlaying = book(series = emptyList()))

            host.querySelector(".np-series") shouldBe null
        }

        test("the chapter line names where you are and how far in") {
            val host = openPanel(chapterList = chapters(76), currentChapterIndex = 11)

            (host.querySelector(".np-ch-n") as HTMLElement).textContent shouldBe "Chapter 12 of 76"
            (host.querySelector(".np-ch-t") as HTMLElement).textContent shouldBe "Chapter 12"
        }

        // "Chapter 1 of 1" states something about a structure the book does not have.
        test("a book with no marks renders no chapter line") {
            val host = openPanel(chapterList = emptyList(), currentChapterIndex = null)

            host.querySelector(".np-ch") shouldBe null
        }

        test("chapter steps move one chapter each way") {
            val sought = mutableListOf<Int>()
            val host = openPanel(chapterList = chapters(5), currentChapterIndex = 2, onSeekToChapter = { sought += it })

            val steps = host.querySelectorAll(".np-chstep")
            (steps.item(0) as HTMLElement).click()
            (steps.item(1) as HTMLElement).click()

            sought shouldBe listOf(1, 3)
        }

        // Genuinely `disabled`, not merely inert: it is the only version a screen reader also hears.
        test("previous is disabled in the first chapter") {
            val host = openPanel(chapterList = chapters(5), currentChapterIndex = 0)

            val steps = host.querySelectorAll(".np-chstep")
            (steps.item(0) as HTMLElement).hasAttribute("disabled") shouldBe true
            (steps.item(1) as HTMLElement).hasAttribute("disabled") shouldBe false
        }

        test("next is disabled in the last chapter") {
            val host = openPanel(chapterList = chapters(5), currentChapterIndex = 4)

            val steps = host.querySelectorAll(".np-chstep")
            (steps.item(0) as HTMLElement).hasAttribute("disabled") shouldBe false
            (steps.item(1) as HTMLElement).hasAttribute("disabled") shouldBe true
        }

        test("Go to book opens the playing book") {
            val opened = mutableListOf<String>()
            val host = openPanel(nowPlaying = book(bookId = "b42"), onOpenBook = { opened += it })

            (host.querySelector(".np-goto") as HTMLElement).click()

            opened shouldBe listOf("b42")
        }

        test("the panel's play control reports a press and says which action it is") {
            var presses = 0
            val host =
                openPanel(
                    state = playing(isPlaying = true),
                    onPlayPause = { presses++ },
                )

            val play = host.querySelector(".np-play") as HTMLElement
            play.getAttribute("aria-label") shouldBe "Pause"
            play.click()

            presses shouldBe 1
        }

        test("the boost chip reads the boost when there is one, and invites one when there is not") {
            val off = openPanel(volumeBoostDb = 0f)
            val on = openPanel(volumeBoostDb = 6f)

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
    })

/**
 * [stepTarget]'s own edges, without a player or a DOM.
 *
 * The disabled-button specs above prove the panel renders what this returns; these prove what it
 * returns is right at every boundary, including the ones a fixture would have to be contrived to
 * reach.
 */
class ChapterStepTargetTest :
    FunSpec({

        test("a step from the middle moves one chapter each way") {
            stepTarget(currentIndex = 3, chapterCount = 10, back = true) shouldBe 2
            stepTarget(currentIndex = 3, chapterCount = 10, back = false) shouldBe 4
        }

        test("there is nothing before the first chapter or after the last") {
            stepTarget(currentIndex = 0, chapterCount = 10, back = true) shouldBe null
            stepTarget(currentIndex = 9, chapterCount = 10, back = false) shouldBe null
        }

        test("a book with no marks has nowhere to step") {
            stepTarget(currentIndex = null, chapterCount = 0, back = true) shouldBe null
            stepTarget(currentIndex = null, chapterCount = 0, back = false) shouldBe null
        }

        test("a position outside the book steps nowhere rather than to the nearest chapter") {
            stepTarget(currentIndex = 40, chapterCount = 10, back = false) shouldBe null
        }
    })
