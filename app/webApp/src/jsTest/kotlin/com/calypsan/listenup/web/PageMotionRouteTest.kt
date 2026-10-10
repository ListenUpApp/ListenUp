package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.motion.forgetHeroFlight
import com.calypsan.listenup.web.motion.forgetPageArrival
import com.calypsan.listenup.web.motion.isOnScreen
import com.calypsan.listenup.web.nav.forgetScrollMemory
import com.calypsan.listenup.web.nav.readLeavingPage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.math.abs

/** Book Detail's fixture is always book "42", so book 42 sits deep in the grid: index 899. */
private const val OPENED_INDEX = 899

private val LIBRARY =
    (1..2_000).map { n ->
        val title = "Book ${n.toString().padStart(4, '0')}"
        if (n == OPENED_INDEX + 1) contractBook("42", title) else contractBook("b$n", title)
    }

private const val OPENED_TITLE = "Book 0900"

private suspend fun awaitUntil(
    what: String,
    condition: () -> Boolean,
) {
    try {
        withTimeout(6_000) { while (!condition()) delay(16) }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for: $what", timeout)
    }
}

/**
 * The journey this PR exists for: deep in a 2,000-book library, open a book, come Back. The reader
 * lands where they were, and the cover flies home into the tile they tapped — not into a stand-in
 * the virtual grid rendered first, and not before the scroll was put back.
 */
class PageMotionRouteTest :
    FunSpec({
        afterTest {
            stopRecordingAnimations()
            forgetScrollMemory()
            forgetHeroFlight()
            forgetPageArrival()
        }

        test("Back to a large library restores the place and flies the cover into its tile") {
            val (host, router, composition) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = LIBRARY)),
                    beforeRouteChange = ::readLeavingPage,
                )
            try {
                val main = host.querySelector(".shell-main") as HTMLElement

                fun card(): HTMLElement? =
                    host.querySelectorAll(".lib-card").asList().map { it as HTMLElement }.firstOrNull {
                        it.querySelector(".lib-title")?.textContent == OPENED_TITLE
                    }

                awaitUntil("the grid to window") { host.querySelector(".vl-spacer") != null }
                val first = host.querySelector(".lib-card") as HTMLElement
                val grid = host.querySelector(".lib-grid") as HTMLElement
                val style = window.getComputedStyle(grid)
                val columns = style.getPropertyValue("grid-template-columns").split(" ").count { it.isNotBlank() }
                val gap = style.getPropertyValue("row-gap").removeSuffix("px").toDoubleOrNull() ?: 0.0
                main.scrollTop = OPENED_INDEX / columns * (first.getBoundingClientRect().height + gap)
                awaitUntil("the opened book's card to render") { card() != null }
                card()!!.scrollIntoView(js("({ block: 'center' })"))
                repeat(3) { awaitFrame() }
                val leftAt = main.scrollTop

                startRecordingAnimations()
                card()!!.click()
                awaitUntil("Book Detail") { host.querySelector(".bd-head") != null }
                main.scrollTop shouldBe 0.0
                // ⛔ Back only once the outbound flight has landed. Back reads the hero where it is
                // DRAWN, so a reversal mid-flight flies home from wherever the cover has got to — and
                // a Back before its first frame finds the cover still standing on its tile, where
                // there is nowhere to fly. Going Back the instant Book Detail mounted raced that
                // frame: here Back usually came first and the outbound flight never started; on CI
                // the flight started, Back measured the cover still on its tile, and nothing flew.
                awaitUntil("the cover to fly out to Book Detail and land") {
                    val hero = host.querySelector(".bd-head .cover") as? HTMLElement
                    hero != null && hero in recordedAnimations() && hero.motions().isEmpty()
                }

                stopRecordingAnimations()
                startRecordingAnimations()
                window.history.back()

                awaitUntil("the library to come back where it was") { abs(main.scrollTop - leftAt) < 1.0 }
                awaitUntil("the cover to fly home into its own tile") {
                    recordedAnimations().any { it.closest(".lib-card")?.querySelector(".lib-title")?.textContent == OPENED_TITLE }
                }
                isOnScreen(card()!!) shouldBe true
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
