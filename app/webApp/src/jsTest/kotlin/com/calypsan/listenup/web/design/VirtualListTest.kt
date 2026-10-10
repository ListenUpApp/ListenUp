package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.browse.GenreDestinationPage
import com.calypsan.listenup.web.features.browse.genreReady
import com.calypsan.listenup.web.features.contributors.ContributorsPage
import com.calypsan.listenup.web.features.contributors.contributor
import com.calypsan.listenup.web.features.library.VirtualBookGrid
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.notifications.NotificationsPage
import com.calypsan.listenup.web.features.notifications.notification
import com.calypsan.listenup.web.features.serieslist.SeriesListPage
import com.calypsan.listenup.web.motion.forgetPageArrival
import com.calypsan.listenup.web.motion.isOnScreen
import com.calypsan.listenup.web.motion.markPageArrival
import com.calypsan.listenup.web.recordedAnimations
import com.calypsan.listenup.web.startRecordingAnimations
import com.calypsan.listenup.web.stopRecordingAnimations
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.ceil

/** A library's worth: enough that rendering all of it is the thing being avoided. */
private const val LARGE = 2_000

/** The window this many items may render — a screenful plus overscan, never the list. */
private const val WINDOW_CAP = 200

private const val DESKTOP_WIDTH = 1280

private const val DESKTOP_HEIGHT = 800

private const val SETTLE_TIMEOUT_MS = 4_000L

/** A window wide and tall enough that its first screenful holds more cards than the first paint. */
private const val WIDE_WIDTH = 2_560

private const val WIDE_HEIGHT = 1_600

/** What VirtualList renders before it has measured anything. */
private const val FIRST_PAINT_CARDS = 24

private fun books(count: Int): List<BookListItem> = (1..count).map { contractBook("b$it", "Book ${it.toString().padStart(4, '0')}") }

private fun seriesList(count: Int): List<SeriesWithBooks> =
    (1..count).map { index ->
        val first = contractBook("s$index-b1", "Series $index book")
        SeriesWithBooks(
            series = Series(id = SeriesId("s$index"), name = "Series ${index.toString().padStart(4, '0')}"),
            books = listOf(first),
            bookSequences = mapOf(first.id.value to null),
        )
    }

/** Waits, frame by frame, until [condition] holds — or fails naming [what]. */
private suspend fun awaitUntil(
    what: String,
    condition: () -> Boolean,
) {
    try {
        withTimeout(SETTLE_TIMEOUT_MS) { while (!condition()) delay(FRAME_MS) }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for: $what", timeout)
    }
}

private const val FRAME_MS = 16L

private fun ViewportFrame.px(
    element: HTMLElement,
    property: String,
): Double = css(element, property).removeSuffix("px").toDoubleOrNull() ?: 0.0

/** Columns the grid is actually laying out, per its own computed style. */
private fun ViewportFrame.columns(grid: HTMLElement): Int = css(grid, "grid-template-columns").split(" ").count { it.isNotBlank() }

/** The list is windowed: measured, with spacers standing in for what is not rendered. */
private fun ViewportFrame.isWindowed(): Boolean = findAll(".vl-spacer").isNotEmpty()

/** Every rendered list item's position in the whole list. */
private fun ViewportFrame.positions(): List<Int> = findAll("[role=listitem]").mapNotNull { it.getAttribute("aria-posinset")?.toIntOrNull() }

/**
 * The windowing primitive under the library, genre, series and contributor lists.
 *
 * What these pin: it re-measures when its container changes width on its own (the sidebar
 * collapsing), it does not recompose while the reader scrolls within a row, its keys keep an item's
 * node as the window moves or the list reorders, and a list of thousands renders a window — with
 * list semantics that still describe the whole, and keyboard focus that can walk past the window's
 * edge.
 */
class VirtualListTest :
    FunSpec({
        val frames = ViewportFrames()
        val mounts = MountRegistry()
        afterTest {
            frames.disposeAll()
            mounts.disposeAll()
            forgetPageArrival()
            stopRecordingAnimations()
        }

        fun grid(
            count: Int,
            probe: (String) -> Unit = {},
        ): ViewportFrame =
            frames.mount(DESKTOP_WIDTH, DESKTOP_HEIGHT) {
                CompositionLocalProvider(LocalCompositionProbe provides probe) {
                    InShell {
                        VirtualBookGrid(books = books(count), letterOf = { null }, statusOf = { null }, onOpenBook = {})
                    }
                }
            }

        /** Whether the grid's height is the height its real column count implies. */
        fun ViewportFrame.heightMatchesColumns(count: Int): Boolean {
            val grid = find(".lib-grid")
            val card = findAll(".lib-card").firstOrNull() ?: return false
            val row = rect(card).height + px(grid, "row-gap")
            val expected = ceil(count / columns(grid).toDouble()) * row
            // Two rows of slack: the spacers are grid items too, and each brings a gap of its own.
            return abs(rect(grid).height - expected) <= 2 * row
        }

        test("the grid re-measures when its container narrows and the window does not") {
            val frame = grid(count = 400)
            awaitUntil("the grid to window") { frame.isWindowed() && frame.heightMatchesColumns(400) }
            val wide = frame.columns(frame.find(".lib-grid"))

            // The sidebar collapsing does exactly this: the content region changes width by 176px
            // and the window does not resize, so a `resize` listener never hears about it.
            frame.find(".shell-main").style.paddingRight = "600px"

            awaitUntil("the grid to lay out for its new width") {
                frame.columns(frame.find(".lib-grid")) < wide && frame.heightMatchesColumns(400)
            }
        }

        test("scrolling within a row does not recompose the list; scrolling past rows does") {
            var compositions = 0
            val frame = grid(count = LARGE) { if (it == VIRTUAL_LIST_PROBE) compositions++ }
            awaitUntil("the grid to window") { frame.isWindowed() }
            val main = frame.find(".shell-main")
            main.scrollTop = 1_000.0
            repeat(3) { awaitFrame() }
            val settled = compositions

            // Twenty pixels in two-pixel steps, each given its frame. The window can move at most
            // twice over that — once at each edge — where writing the scroll offset as state
            // recomposed the list on every one of the ten.
            repeat(10) {
                main.scrollTop += 2.0
                awaitFrame()
            }

            withClue("recompositions while scrolling 20px") { compositions - settled shouldBeLessThanOrEqual 2 }

            val beforeLongScroll = compositions
            main.scrollTop += 3_000.0
            awaitUntil("a long scroll to move the window") { compositions > beforeLongScroll }
        }

        test("a card keeps its DOM node as the window moves past it") {
            val frame = grid(count = LARGE)
            awaitUntil("the grid to window") { frame.isWindowed() }
            val main = frame.find(".shell-main")
            main.scrollTop = 2_000.0
            awaitUntil("the window to follow the scroll") { frame.positions().min() > 1 }
            repeat(3) { awaitFrame() }

            // A card in the middle of the viewport, then the view moves up by a couple of rows: the
            // card stays inside the overscan, while the rows above it leave the window.
            val middle = frame.rect(main).top + frame.rect(main).height / 2
            val card = frame.findAll(".lib-card").first { frame.rect(it).bottom > middle }
            val title = card.querySelector(".lib-title")!!.textContent
            val firstBefore = frame.positions().min()
            main.scrollTop += 700.0
            awaitUntil("rows above to leave the window") { frame.positions().min() > firstBefore }

            val after = frame.findAll(".lib-card").first { it.querySelector(".lib-title")?.textContent == title }
            after shouldBeSameInstanceAs card
        }

        test("a list arriving with its page sweeps its whole first screenful in, top to bottom") {
            startRecordingAnimations()
            val frame =
                frames.mount(WIDE_WIDTH, WIDE_HEIGHT) {
                    InShell {
                        VirtualBookGrid(books = books(LARGE), letterOf = { null }, statusOf = { null }, onOpenBook = {})
                    }
                }
            // Before the mount's microtask runs: the list mounts as part of a page arrival.
            markPageArrival()
            awaitUntil("the grid to window") { frame.isWindowed() }
            repeat(2) { awaitFrame() }

            val onScreen = frame.findAll(".lib-card").filter(::isOnScreen)
            // More than the first paint renders, or this pins nothing.
            onScreen.size shouldBeGreaterThan FIRST_PAINT_CARDS
            val animated = recordedAnimations()
            // Where each card's latest sweep was started, in call order: reading order means rising.
            val lastSwept = onScreen.map { card -> animated.indexOfLast { it === card } }
            withClue("cards never swept: ${lastSwept.count { it < 0 }} of ${onScreen.size}") { lastSwept.none { it < 0 } shouldBe true }
            withClue("sweep order: $lastSwept") { lastSwept.zipWithNext().all { (a, b) -> b > a } shouldBe true }
        }

        test("a keyed list keeps each item's node when the list reorders") {
            val items = mutableStateOf(listOf("a", "b", "c"))
            val host =
                mounts.mount {
                    VirtualList(
                        items = items.value,
                        key = { it },
                        containerClass = "lib-grid",
                        itemSelector = ".probe",
                        label = "Letters",
                    ) { letter -> Div(attrs = { attr("data-letter", letter) }) { Text(letter) } }
                }
            awaitFrame()
            val a = host.querySelector("[data-letter=a]")

            items.value = listOf("c", "a", "b")
            awaitFrame()

            host.querySelectorAll("[data-letter]").item(0)?.textContent shouldBe "c"
            host.querySelector("[data-letter=a]") shouldBeSameInstanceAs a
        }

        test("a notification keeps its row's node when a newer one arrives above it") {
            val state = mutableStateOf(NotificationsUiState.Data(listOf(notification(id = "n1"))))
            val host = mounts.mount { NotificationsPage(state = state.value, nowMs = 0L, onOpen = {}) }
            awaitFrame()
            val row = host.querySelector(".ntf-row")

            state.value = NotificationsUiState.Data(listOf(notification(id = "n2"), notification(id = "n1")))
            awaitFrame()

            host.querySelectorAll(".ntf-row").length shouldBe 2
            host.querySelectorAll(".ntf-row").item(1) shouldBeSameInstanceAs row
        }

        /**
         * The contract every long list owes: a window, not the list; list semantics that describe
         * the whole; and a keyboard that can walk out of the window.
         */
        suspend fun assertWindowedAndReachable(
            frame: ViewportFrame,
            itemSelector: String,
        ) {
            awaitUntil("the list to window") { frame.isWindowed() }
            val rendered = frame.findAll(itemSelector).size
            withClue("items rendered of $LARGE") {
                rendered shouldBeGreaterThan 0
                rendered shouldBeLessThan WINDOW_CAP
            }

            val list = frame.find("[role=list]")
            list.getAttribute("aria-label").shouldNotBeNull()
            val items = frame.findAll("[role=listitem]")
            items.first().getAttribute("aria-setsize") shouldBe LARGE.toString()
            frame.positions().min() shouldBe 1

            // Tab walks to the next rendered item, and focusing scrolls it into view — so the
            // window has to have moved on by the time the reader gets there. Walk to the last
            // rendered item and check that items past the original edge now exist to Tab to.
            val edge = frame.positions().max()
            val last = frame.findAll("[role=listitem] $itemSelector").last()
            last.focus()
            frame.host.ownerDocument!!.activeElement shouldBeSameInstanceAs last
            awaitUntil("items past the window's edge to render") { frame.positions().max() > edge }
            val next =
                frame
                    .findAll("[role=listitem]")
                    .first { it.getAttribute("aria-posinset")!!.toInt() == edge + 1 }
                    .querySelector(itemSelector) as HTMLElement
            next.focus()
            frame.host.ownerDocument!!.activeElement shouldBeSameInstanceAs next
        }

        fun inShell(content: @Composable () -> Unit): ViewportFrame = frames.mount(DESKTOP_WIDTH, DESKTOP_HEIGHT) { InShell(content) }

        test("a genre of thousands renders a window of its books, reachable by keyboard") {
            val shelf = books(LARGE)
            val frame =
                inShell {
                    GenreDestinationPage(
                        state = genreReady(books = shelf, bookCount = shelf.size),
                        onOpenBook = {},
                        onOpenGenre = {},
                        onOpenLibrary = {},
                        onToggleSubGenres = {},
                    )
                }
            assertWindowedAndReachable(frame, ".lib-card")
        }

        test("a library of thousands of series renders a window of them, reachable by keyboard") {
            val frame =
                inShell {
                    SeriesListPage(
                        state = contractLibrary(series = seriesList(LARGE)),
                        onEvent = {},
                        onOpenSeries = {},
                        onSelectFacet = {},
                    )
                }
            assertWindowedAndReachable(frame, ".srs-card")
        }

        test("thousands of contributors render a window of them, reachable by keyboard") {
            val people =
                (1..LARGE).map { index ->
                    val letter = 'A' + index * 26 / (LARGE + 1)
                    contributor("c$index", "$letter Person ${index.toString().padStart(4, '0')}")
                }
            val frame =
                inShell {
                    ContributorsPage(
                        state = people,
                        role = ContributorRole.AUTHOR,
                        onSelectFacet = {},
                        onOpenContributor = {},
                    )
                }
            awaitUntil("the list to window") { frame.isWindowed() }
            // Headings are a visual index, never list items of their own.
            frame.findAll(".contrib-letter").size shouldNotBe 0
            frame.findAll(".vl-head").forEach { it.getAttribute("aria-hidden") shouldBe "true" }
            assertWindowedAndReachable(frame, ".contrib-row")
        }
    })
