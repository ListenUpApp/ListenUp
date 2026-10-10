package com.calypsan.listenup.web.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.library.VirtualBookGrid
import com.calypsan.listenup.web.features.library.contractBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

private val LIBRARY: List<BookListItem> = (1..2_000).map { contractBook("b$it", "Book ${it.toString().padStart(4, '0')}") }

/** A page for each shape the restoration has to handle: tall, short, one that grows late, a virtual grid. */
@Composable
private fun Page(
    route: Route,
    grown: Boolean,
) {
    when (route.segments.firstOrNull()) {
        "library" -> VirtualBookGrid(books = LIBRARY, letterOf = { null }, statusOf = { null }, onOpenBook = {})
        "short" -> Spacer(200)
        else -> Spacer(if (grown) 6_000 else 200)
    }
}

/** A block [px] tall. The region is a flex column, so an empty box must refuse to shrink to keep its height. */
@Composable
private fun Spacer(px: Int) {
    Div(attrs = {
        style {
            height(px.px)
            property("flex-shrink", "0")
        }
    })
}

private suspend fun awaitUntil(
    what: String,
    condition: () -> Boolean,
) {
    try {
        withTimeout(4_000) { while (!condition()) delay(16) }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for: $what", timeout)
    }
}

private fun ViewportFrame.positions(): List<Int> = findAll("[role=listitem]").mapNotNull { it.getAttribute("aria-posinset")?.toIntOrNull() }

/**
 * Back returns the reader to the place they left — in memory, per URL, and through the virtualised
 * grid, whose height does not exist until it has measured itself a frame or two after mounting.
 *
 * Each spec drives the router's own hook ([captureScrollBeforeRouteChange]) and then moves the
 * route, which is exactly the order the real router uses.
 */
class ScrollMemoryTest :
    FunSpec({
        val frames = ViewportFrames()
        val route = mutableStateOf(Route(listOf("tall")))
        val grown = mutableStateOf(true)

        afterTest {
            frames.disposeAll()
            forgetScrollMemory()
            route.value = Route(listOf("tall"))
            grown.value = true
        }

        fun mount(): HTMLElement {
            val frame =
                frames.mount(1280, 800) {
                    InShell {
                        ScrollRestoration(route.value)
                        Page(route.value, grown.value)
                    }
                }
            return frame.find(".shell-main")
        }

        fun go(
            change: RouteChange,
            to: Route,
        ) {
            captureScrollBeforeRouteChange(change)
            route.value = to
        }

        test("Back returns to the offset a page was left at") {
            val main = mount()
            awaitFrame()
            main.scrollTop = 1_500.0

            go(RouteChange.PUSH, Route(listOf("short")))
            awaitUntil("the short page") { main.scrollHeight < 1_000 }
            go(RouteChange.POP, Route(listOf("tall")))

            awaitUntil("the offset to come back") { main.scrollTop == 1_500.0 }
        }

        test("Back returns to the same place in a virtualised grid") {
            route.value = Route(listOf("library"))
            val main = mount()
            val frame = frames.last()
            awaitUntil("the grid to window") { frame.findAll(".vl-spacer").isNotEmpty() }
            main.scrollTop = 6_000.0
            awaitUntil("the window to follow") { frame.positions().min() > 1 }

            go(RouteChange.PUSH, Route(listOf("short")))
            awaitUntil("the grid to go") { frame.findAll(".lib-card").isEmpty() }
            go(RouteChange.POP, Route(listOf("library")))

            awaitUntil("the grid to come back where it was") {
                main.scrollTop == 6_000.0 && frame.positions().min() > 1
            }
        }

        test("a query-only change keeps the reader's place") {
            val main = mount()
            awaitFrame()
            main.scrollTop = 1_500.0

            go(RouteChange.REPLACE, Route(listOf("tall"), mapOf("sort" to "added")))
            repeat(3) { awaitFrame() }

            main.scrollTop shouldBe 1_500.0
        }

        test("a link to a different page starts at its top") {
            val main = mount()
            awaitFrame()
            main.scrollTop = 1_500.0

            go(RouteChange.PUSH, Route(listOf("another-tall")))

            awaitUntil("the new page to start at the top") { main.scrollTop == 0.0 }
        }

        test("the reader's own scroll during a restoration wins") {
            val main = mount()
            awaitFrame()
            main.scrollTop = 3_000.0
            go(RouteChange.PUSH, Route(listOf("short")))
            awaitFrame()

            grown.value = false
            go(RouteChange.POP, Route(listOf("tall")))
            awaitFrame()
            main.dispatchEvent(Event("wheel"))
            grown.value = true
            repeat(5) { awaitFrame() }

            main.scrollTop shouldBe 0.0
        }

        test("leaving while a place is still being restored keeps the place") {
            val main = mount()
            awaitFrame()
            main.scrollTop = 1_500.0
            go(RouteChange.PUSH, Route(listOf("short")))
            awaitUntil("the short page") { main.scrollHeight < 1_000 }

            // Back to a page not yet tall enough: the restoration is still waiting for height when
            // the reader clicks away again, with the region sitting at 0.
            grown.value = false
            go(RouteChange.POP, Route(listOf("tall")))
            awaitFrame()
            go(RouteChange.PUSH, Route(listOf("short")))
            awaitFrame()

            grown.value = true
            go(RouteChange.POP, Route(listOf("tall")))

            awaitUntil("the original offset to come back") { main.scrollTop == 1_500.0 }
        }

        test("a replace to a different page starts at its top") {
            val main = mount()
            awaitFrame()
            main.scrollTop = 1_500.0

            go(RouteChange.REPLACE, Route(listOf("another-tall")))

            awaitUntil("the new page to start at the top") { main.scrollTop == 0.0 }
        }

        test("an arrival outside any scroller still settles") {
            captureScrollBeforeRouteChange(RouteChange.POP)
            frames.mount(1280, 800) { ScrollRestoration(route.value) }
            awaitFrame()
            var ran = false

            whenScrollSettled { ran = true }

            ran shouldBe true
        }

        test("work queued while a place is restored runs once it is") {
            var ran = false
            captureScrollBeforeRouteChange(RouteChange.POP)

            whenScrollSettled { ran = true }
            ran shouldBe false

            settleScroll()
            ran shouldBe true
        }
    })
