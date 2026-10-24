package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.NoCoverThemes
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.contrast
import com.calypsan.listenup.web.design.LocalCoverThemes
import com.calypsan.listenup.web.over
import com.calypsan.listenup.web.rgb
import com.calypsan.listenup.web.rgba
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The wide Now Playing page's accessibility (spec §8, PR 5): the keyboard walks the book, then the chapters, and the
 * current row's progress bar adds no stop and no name; the bar under the page keeps its pinned order on `/playing`;
 * every word of the list reads at 4.5:1 on the cover wash and the bar at 3:1, in both themes; the rows are 44px
 * targets; and at 200% text the list scrolls while the page never scrolls sideways.
 */
class NowPlayingWideA11yTest :
    FunSpec({
        val frames = ViewportFrames()
        val mounts = MountRegistry()
        afterTest {
            frames.disposeAll()
            mounts.disposeAll()
        }

        /** The Way of Kings, 68 chapters, at chapter 39 with 8m 31s of it left. */
        val book =
            wayOfKings(
                narrators = listOf(PlayerContributor("n1", "Michael Kramer", 1), PlayerContributor("n2", "Kate Reading", 1)),
                chapters = chapterRows(68, CHAPTER_MS),
                currentChapterIndex = CURRENT,
            )
        val progress = PlayerProgress(CHAPTER_MS - LEFT_MS, CHAPTER_MS, CURRENT * CHAPTER_MS, KINGS_MS)

        fun page(
            width: Int = DESKTOP_WIDTH,
            height: Int = DESKTOP_HEIGHT,
        ): ViewportFrame =
            frames.mount(width = width, height = height) {
                CompositionLocalProvider(LocalCoverThemes provides NoCoverThemes) {
                    NowPlayingPage(state = book, progress = progress, actions = NowPlayingActions())
                }
            }

        test("the keyboard walks Back, More, the series, the title, the people, then every chapter, in that order") {
            val frame = page()
            awaitFrame()
            frame.focusableNames(frame.host) shouldBe
                listOf(
                    "Back",
                    "More actions for The Way of Kings",
                    "The Stormlight Archive · Book 1",
                    "Go to book: The Way of Kings",
                    "Brandon Sanderson",
                    "Michael Kramer",
                    "Kate Reading",
                ) + book.chapters.map { PlayerSheetWords.chapterRowLabel(it, it.index == CURRENT, LEFT_MS) }
        }

        test("Back to current chapter, when offered, is the last stop, after the rows") {
            val frame = page()
            awaitFrame()
            awaitFrame()
            val list = frame.find(".np-list")
            list.scrollTop = list.scrollHeight.toDouble()
            awaitFrame()
            awaitFrame()
            frame.focusableNames(frame.host).last() shouldBe NowPlayingWords.BACK_TO_CURRENT
        }

        test("the current row's bar is decoration: hidden from assistive tech, with nothing in it to focus") {
            val frame = page()
            val bars = frame.findAll(".np-row-bar")
            bars.size shouldBe 1
            bars.single().getAttribute("aria-hidden") shouldBe "true"
            bars.single().querySelectorAll("a, button, input, [tabindex]").length shouldBe 0
            frame.find(".np-listhead h2").tabIndex shouldBe -1
        }

        test("on /playing the bar keeps its pinned order; only Open turns into Close") {
            val host =
                mounts.mount {
                    TransportBar(
                        state = book,
                        progress = progress,
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                        isNowPlayingOpen = true,
                        onFocusChapters = {},
                    )
                }
            val bar = host.querySelector(".tport") as HTMLElement
            bar
                .querySelectorAll("a, button, input")
                .asList()
                .map { it as HTMLElement }
                .filter { it.tabIndex >= 0 }
                .map { it.getAttribute("aria-label") } shouldBe
                listOf(
                    "Open Now Playing: The Way of Kings, Chapter 39, 8 minutes left in the chapter",
                    "Skip back 10 seconds",
                    "Pause",
                    "Skip forward 30 seconds",
                    "Chapter position",
                    "Playback speed, 1 times",
                    "Sleep timer",
                    "Chapters",
                    "Close Now Playing",
                )
        }

        listOf(false to LIGHT_COVER_WASHES, true to DARK_COVER_WASHES).forEach { (dark, covers) ->
            val theme = if (dark) "dark" else "light"

            test("$theme: the list's words read at 4.5:1 on its ground over the neutral and every cover wash") {
                (listOf<String?>(null) + covers).forEach { wash ->
                    val frame = washedList(frames, dark, wash)
                    val washColour = rgb(frame.css(frame.find(".np-page"), "background-color"))
                    val listGround = over(rgba(frame.css(frame.find(".np-list"), "background-color")), washColour)
                    val row = frame.findAll(".np-row").first { !it.classList.contains("on") }
                    listOf(".np-row-t", ".np-row-n", ".np-row-d").forEach { part ->
                        val ink = rgb(frame.css(row.querySelector(part)!!, "color"))
                        withClue("$part on the list over ${wash ?: "the neutral"} ($theme)") {
                            contrast(ink, listGround) shouldBeGreaterThanOrEqual AA_TEXT
                        }
                    }
                    listOf(".np-listhead h2", ".np-listhead .np-listsum").forEach { part ->
                        val ink = rgb(frame.css(frame.find(part), "color"))
                        withClue("$part on ${wash ?: "the neutral"} ($theme)") {
                            contrast(ink, washColour) shouldBeGreaterThanOrEqual AA_TEXT
                        }
                    }
                    frames.disposeAll()
                }
            }

            test("$theme: the current row's words read at 4.5:1, and its bar at 3:1 against its track and the row") {
                val frame = washedList(frames, dark, wash = null)
                val current = frame.find(".np-row.on")
                val coral = rgb(frame.css(current, "background-color"))
                val words = rgb(frame.css(current, "color"))
                val track = rgb(frame.css(frame.find(".np-row-bar"), "background-color"))
                val fill = rgb(frame.css(frame.find(".np-row-fill"), "background-color"))
                withClue("words on the coral row ($theme)") { contrast(words, coral) shouldBeGreaterThanOrEqual AA_TEXT }
                withClue("fill on its track ($theme)") { contrast(fill, track) shouldBeGreaterThanOrEqual NON_TEXT }
                withClue("fill on the coral row ($theme)") { contrast(fill, coral) shouldBeGreaterThanOrEqual NON_TEXT }
            }

            test("$theme: Back to current chapter reads at 4.5:1 on its own surface") {
                val frame = washedList(frames, dark, wash = null)
                val back = frame.find(".np-page > .np-back-current")
                contrast(rgb(frame.css(back, "color")), rgb(frame.css(back, "background-color"))) shouldBeGreaterThanOrEqual
                    AA_TEXT
            }
        }

        test("every chapter row and Back to current chapter is at least 44px tall") {
            val frame = page()
            awaitFrame()
            awaitFrame()
            frame.findAll(".np-row").forEach { row ->
                withClue(row.getAttribute("aria-label")) { frame.rect(row).height shouldBeGreaterThanOrEqual MIN_TARGET }
            }
            val list = frame.find(".np-list")
            list.scrollTop = list.scrollHeight.toDouble()
            awaitFrame()
            awaitFrame()
            frame.rect(frame.find(".np-back-current")).height shouldBeGreaterThanOrEqual MIN_TARGET
        }

        listOf(DESKTOP_WIDTH to DESKTOP_HEIGHT, SMALL_DESKTOP_WIDTH to SMALL_DESKTOP_HEIGHT).forEach { (width, height) ->
            test("at 200% text and ${width}px the list scrolls, the page never scrolls sideways, and the bar stays in its row") {
                val frame = page(width, height)
                frame.host.ownerDocument!!
                    .documentElement!!
                    .unsafeCast<HTMLElement>()
                    .style.fontSize = DOUBLE_TEXT
                awaitFrame()
                awaitFrame()
                val list = frame.find(".np-list")
                frame.css(list, "overflow-y") shouldBe "auto"
                list.scrollHeight shouldBeGreaterThan list.clientHeight
                frame.horizontalOverflow() shouldBeLessThanOrEqual 0
                val row = frame.rect(frame.find(".np-row.on"))
                listOf(frame.find(".np-row-bar"), frame.find(".np-row-now")).forEach { part ->
                    val box = frame.rect(part)
                    withClue(part.className) {
                        box.left shouldBeGreaterThanOrEqual row.left
                        box.right shouldBeLessThanOrEqual row.right
                        box.top shouldBeGreaterThanOrEqual row.top
                        box.bottom shouldBeLessThanOrEqual row.bottom
                    }
                }
            }
        }
    })

/** The chapter list as the page draws it, on the wash, with a probe of Back to current chapter beside it. */
private fun washedList(
    frames: ViewportFrames,
    dark: Boolean,
    wash: String?,
): ViewportFrame {
    val frame =
        frames.mount(width = DESKTOP_WIDTH, height = DESKTOP_HEIGHT) {
            Div(attrs = {
                classes("np-page")
                if (wash != null) {
                    classes("cover-themed")
                    style {
                        property("--cover-wash-l", wash)
                        property("--cover-wash-d", wash)
                    }
                }
            }) {
                NowPlayingChapterList(
                    rows = chapterRows(8, CHAPTER_MS).map { it.copy(isCurrent = it.index == 2) },
                    currentIndex = 2,
                    chapterRemainingMs = LEFT_MS,
                    chapterFraction = 0.59f,
                    bookDurationMs = KINGS_MS,
                    onPick = {},
                )
                Button(attrs = { classes("np-back-current") }) { Text(NowPlayingWords.BACK_TO_CURRENT) }
            }
        }
    val root = frame.host.ownerDocument!!.documentElement!!
    if (dark) root.setAttribute("data-theme", "dark") else root.removeAttribute("data-theme")
    return frame
}

/** The accessible names of everything Tab reaches inside [scope], in order: shown, and not taken out of the order. */
private fun ViewportFrame.focusableNames(scope: Element): List<String> =
    scope
        .querySelectorAll("a, button, input, select, textarea, [tabindex]")
        .asList()
        .filterIsInstance<HTMLElement>()
        .filter { it.tabIndex >= 0 && isShown(it) && !it.hasAttribute("disabled") }
        .map { it.getAttribute("aria-label") ?: it.textContent.orEmpty().trim() }

private const val CHAPTER_MS = 1_235_000L
private const val LEFT_MS = 511_000L
private const val CURRENT = 38
private const val DESKTOP_WIDTH = 1440
private const val DESKTOP_HEIGHT = 900
private const val SMALL_DESKTOP_WIDTH = 1024
private const val SMALL_DESKTOP_HEIGHT = 768
private const val DOUBLE_TEXT = "32px"
private const val MIN_TARGET = 44.0
private const val AA_TEXT = 4.5
private const val NON_TEXT = 3.0
