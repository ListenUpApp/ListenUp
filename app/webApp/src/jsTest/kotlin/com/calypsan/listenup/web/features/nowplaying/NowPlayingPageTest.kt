package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.FakeCoverThemes
import com.calypsan.listenup.web.KINGS_THEMES
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.NoCoverThemes
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.LocalCoverThemes
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

/**
 * The Now Playing page's book (§5.4): the title and cover are one-click Go to book links, every person and series is a
 * link, the status is a live region outside the heading, and the page takes its wash from the cover.
 */
class NowPlayingPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun pageWithThemes(
            state: PlayerUiState.Loaded,
            progress: PlayerProgress = PlayerProgress.Zero,
            themes: CoverThemeRepository = NoCoverThemes,
            actions: NowPlayingActions = NowPlayingActions(),
        ): HTMLElement =
            mounts.mount {
                CompositionLocalProvider(LocalCoverThemes provides themes) {
                    NowPlayingPage(state = state, progress = progress, actions = actions)
                }
            }

        fun page(
            state: PlayerUiState.Loaded,
            progress: PlayerProgress = PlayerProgress.Zero,
            onOpenBook: (String) -> Unit = {},
            onOpenContributor: (String) -> Unit = {},
            onOpenSeries: (String) -> Unit = {},
        ): HTMLElement =
            pageWithThemes(
                state,
                progress,
                actions =
                    NowPlayingActions(
                        onOpenBook = onOpenBook,
                        onOpenContributor = onOpenContributor,
                        onOpenSeries = onOpenSeries,
                    ),
            )

        test("the title is a one-click Go to book link, and the cover links there without a second tab stop") {
            val opened = mutableListOf<String>()
            val host = page(wayOfKings(bookId = "b7"), onOpenBook = { opened += it })
            val title = host.querySelector(".np-title a") as HTMLAnchorElement
            title.getAttribute("href") shouldBe "/book/b7"
            title.getAttribute("aria-label") shouldBe "Go to book: The Way of Kings"
            title.click()
            opened shouldBe listOf("b7")
            val cover = host.querySelector("a.np-cover") as HTMLAnchorElement
            cover.getAttribute("href") shouldBe "/book/b7"
            cover.getAttribute("tabindex") shouldBe "-1"
            cover.getAttribute("aria-hidden") shouldBe "true"
            cover.click()
            opened shouldBe listOf("b7", "b7")
        }

        test("the cover's URL carries the book's hash, so a re-cover is not served from a year-old cache") {
            val host = page(wayOfKings(bookId = "b7", coverHash = "deadbeef"))
            val cover = host.querySelector("a.np-cover img") as HTMLImageElement
            cover.src shouldContain "/api/v1/books/b7/cover"
            cover.src shouldContain "v=deadbeef"
        }

        test("the heading is Now playing, and the status is a live region outside it") {
            val host = page(wayOfKings())
            (host.querySelector("h1") as HTMLElement).textContent shouldBe "Now playing"
            // The page's one H1 is the shared PageHeader's, so focus-on-navigation and the tab name find it.
            (host.querySelector("h1") as HTMLElement).className shouldBe "page-t"
            val status = host.querySelector("[role=status].np-status") as HTMLElement
            status.textContent shouldBe "Streaming"
            status.closest("h1") shouldBe null
        }

        test("every author and narrator is a link to that person") {
            val opened = mutableListOf<String>()
            val host =
                page(
                    wayOfKings(
                        narrators =
                            listOf(PlayerContributor("n1", "Michael Kramer", 1), PlayerContributor("n2", "Kate Reading", 1)),
                    ),
                    onOpenContributor = { opened += it },
                )
            host.querySelectorAll(".np-by a").length shouldBe 1
            host.querySelectorAll(".np-read-by a").length shouldBe 2
            val kramer = host.querySelector(".np-read-by a") as HTMLAnchorElement
            kramer.getAttribute("href") shouldBe "/contributor/n1"
            kramer.click()
            opened shouldBe listOf("n1")
        }

        test("a book with no author renders no byline rather than an empty line") {
            page(wayOfKings(authors = emptyList())).querySelector(".np-by") shouldBe null
        }

        test("more than three narrators fold to the lead and a count, which opens the full list") {
            val host = page(wayOfKings(narrators = (1..5).map { PlayerContributor("n$it", "Narrator $it", it) }))
            val fold = host.querySelector(".np-read-by .np-fold") as HTMLButtonElement
            fold.textContent shouldBe "Narrator 1 & 4 more"
            fold.click()
            awaitFrame()
            host.querySelectorAll(".np-read-by .menu a").length shouldBe 5
        }

        test("every series is named with this book's place, each a link") {
            val opened = mutableListOf<String>()
            val host =
                page(
                    wayOfKings(
                        series =
                            listOf(
                                PlayerSeriesRef("s1", "The Stormlight Archive", "1"),
                                PlayerSeriesRef("s2", "The Cosmere", "7"),
                            ),
                    ),
                    onOpenSeries = { opened += it },
                )
            val links = host.querySelectorAll(".np-series a")
            links.length shouldBe 2
            (links.item(0) as HTMLElement).textContent shouldBe "The Stormlight Archive · Book 1"
            (links.item(1) as HTMLElement).getAttribute("href") shouldBe "/series/s2"
            (links.item(1) as HTMLElement).click()
            opened shouldBe listOf("s2")
        }

        test("a standalone book renders no series line") {
            page(wayOfKings(series = emptyList())).querySelector(".np-series") shouldBe null
        }

        test("the book card states the chapter, the time left in it, a passive book bar and the sentence") {
            val host =
                page(
                    wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5),
                    progress = PlayerProgress(724_000, 1_235_000, 18_000_000, KINGS_MS),
                )
            (host.querySelector(".np-bookcard .np-ch") as HTMLElement).textContent shouldBe "Chapter 6 of 75 · Chapter 6"
            (host.querySelector(".np-bookcard .np-left") as HTMLElement).textContent shouldBe "8m left in chapter"
            (host.querySelector(".np-bookcard [role=img]") as HTMLElement).getAttribute("aria-label") shouldBe
                "Book progress 10 percent"
            (host.querySelector(".np-bookcard .np-sentence") as HTMLElement).textContent shouldBe "Book 10% · 40h 30m left"
        }

        test("a generic chapter title stands alone, with no counter to disagree with it") {
            val host = page(wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5, genericTitles = true))
            (host.querySelector(".np-bookcard .np-ch") as HTMLElement).textContent shouldBe "Chapter 6"
        }

        test("a chapterless book reads Whole book") {
            val host = page(wayOfKings(chapters = emptyList()))
            (host.querySelector(".np-bookcard .np-ch") as HTMLElement).textContent shouldBe "Whole book"
        }

        test("a cover with colours themes the page; one without stays on the neutral") {
            pageWithThemes(wayOfKings(coverHash = "abc"), themes = FakeCoverThemes(KINGS_THEMES))
                .querySelector(".np-page.cover-themed")
                .shouldNotBeNull()
            pageWithThemes(wayOfKings(coverHash = "abc"), themes = NoCoverThemes).querySelector(".np-page.cover-themed") shouldBe
                null
        }
    })

/** Five narrators, the first with fourteen books in this role and the last with none. */
private val FIVE_NARRATORS =
    listOf(
        PlayerContributor("n1", "Michael Kramer", 14),
        PlayerContributor("n2", "Kate Reading", 1),
        PlayerContributor("n3", "Narrator 3", 2),
        PlayerContributor("n4", "Narrator 4", 3),
        PlayerContributor("n5", "Narrator 5", 0),
    )

/**
 * The byline's people with their books (PK1, C12), at a phone's width and a desktop's: "& 4 more" opens a list of links,
 * each a full 44px target with the person's books under the name; and the inline links are 44px targets too (§8).
 */
class NowPlayingBylineTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        listOf(PHONE to 844, 1440 to 900).forEach { (width, height) ->
            test("at ${width}px five narrators fold, and the list shows each person's books as a 44px link") {
                val frame =
                    frames.mount(width, height) {
                        CompositionLocalProvider(LocalCoverThemes provides NoCoverThemes) {
                            NowPlayingPage(state = wayOfKings(narrators = FIVE_NARRATORS), progress = PlayerProgress.Zero, actions = NowPlayingActions())
                        }
                    }
                val fold = frame.find(".np-read-by .np-fold")
                fold.textContent shouldBe "Michael Kramer & 4 more"
                frame.rect(fold).height shouldBeGreaterThanOrEqual 44.0

                fold.click()
                awaitFrame()

                val links = frame.findAll(".np-read-by .menu a")
                links.size shouldBe 5
                links.forEach { link -> frame.rect(link).height shouldBeGreaterThanOrEqual 44.0 }
                links.first().querySelector(".menu-i-d")?.textContent shouldBe "14 books"
                links.first().getAttribute("aria-label") shouldBe "Michael Kramer, 14 books"
                links.first().getAttribute("href") shouldBe "/contributor/n1"
                links.last().querySelector(".menu-i-d") shouldBe null
                links.last().getAttribute("href") shouldBe "/contributor/n5"
            }

            test("at ${width}px the byline's inline links are 44px targets") {
                val frame =
                    frames.mount(width, height) {
                        CompositionLocalProvider(LocalCoverThemes provides NoCoverThemes) {
                            NowPlayingPage(state = wayOfKings(), progress = PlayerProgress.Zero, actions = NowPlayingActions())
                        }
                    }
                frame.findAll(".np-by a, .np-read-by a").forEach { link ->
                    frame.rect(link).height shouldBeGreaterThanOrEqual 44.0
                }
            }
        }
    })
