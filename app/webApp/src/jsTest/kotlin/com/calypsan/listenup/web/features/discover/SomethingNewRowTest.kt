package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.web.Pointer
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.motion.reducedMotionOverride
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement

/**
 * "Something new" is one row at every width: it never wraps, it scrolls sideways with the next cover
 * peeking, and a pointer that cannot swipe gets back and forward arrows that stop at the ends.
 */
class SomethingNewRowTest :
    FunSpec({
        val frames = ViewportFrames()
        beforeTest { reducedMotionOverride = true }
        afterTest {
            frames.disposeAll()
            reducedMotionOverride = null
        }

        test("at 1440 the ten picks are one row that scrolls sideways, and the page does not") {
            val frame = frames.mount(width = 1440, height = 1600) { discoverPage(books = picks(10)) }
            awaitFrame()
            val cards = frame.findAll(".disc-picks .disc-card")
            cards.size shouldBe 10
            val firstTop = frame.rect(cards.first()).top
            cards.forEach { frame.rect(it).top shouldBe (firstTop plusOrMinus 1.0) }
            val row = frame.find(".disc-picks")
            row.scrollWidth shouldBeGreaterThan row.clientWidth
            // The next cover peeks: a card straddles the row's trailing edge.
            val edge = frame.rect(row).right
            cards.any { frame.rect(it).left < edge && frame.rect(it).right > edge } shouldBe true
            frame.horizontalOverflow() shouldBe 0
        }

        test("at 390 every pick is there to scroll to, and nothing scrolls the page sideways") {
            val frame = frames.mount(width = 390, height = 1600, pointer = Pointer.Touch) { discoverPage(books = picks(10)) }
            awaitFrame()
            val cards = frame.findAll(".disc-picks .disc-card")
            cards.size shouldBe 10
            cards.all { frame.isShown(it) } shouldBe true
            frame.horizontalOverflow() shouldBe 0
        }

        test("on a touchscreen the arrows step aside: a swipe moves the row") {
            val frame = frames.mount(width = 390, height = 1600, pointer = Pointer.Touch) { discoverPage(books = picks(10)) }
            awaitFrame()
            frame.findAll(".scroll-row-arrows").all { !frame.isShown(it) } shouldBe true
        }

        test("back is unavailable at the start; forward pages the row until the end, where it stops") {
            val frame = frames.mount(width = 1440, height = 1600) { discoverPage(books = picks(10)) }
            awaitFrame()
            val back = frame.find("[aria-label='Previous books']")
            val forward = frame.find("[aria-label='Next books']")
            frame.isShown(forward) shouldBe true
            back.getAttribute("aria-disabled") shouldBe "true"
            forward.getAttribute("aria-disabled") shouldBe null

            val row = frame.find(".disc-picks")
            forward.click()
            awaitAttribute(back, "aria-disabled", null)
            row.scrollLeft shouldBeGreaterThan 0.0
            // The card that peeked now leads the row: a page, not a nudge.
            val leading = frame.findAll(".disc-picks .disc-card").first { frame.rect(it).right > frame.rect(row).left + 1 }
            frame.rect(leading).left shouldBe (frame.rect(row).left + 6.0 plusOrMinus 2.0)

            var presses = 1
            while (forward.getAttribute("aria-disabled") != "true") {
                forward.click()
                awaitFrame()
                presses++
                presses shouldBeLessThanOrEqual 10
            }
            (row.scrollLeft + row.clientWidth) shouldBe (row.scrollWidth.toDouble() plusOrMinus 1.0)
            back.click()
            awaitAttribute(forward, "aria-disabled", null)
        }

        test("the arrows are named buttons a keyboard reaches") {
            val frame = frames.mount(width = 1440, height = 1600) { discoverPage(books = picks(10)) }
            awaitFrame()
            listOf("Previous books", "Next books").forEach { name ->
                val arrow = frame.find("[aria-label='$name']")
                arrow.tagName shouldBe "BUTTON"
                arrow.getAttribute("type") shouldBe "button"
                arrow.tabIndex shouldBe 0
            }
        }

        test("when every pick fits there are no arrows") {
            val frame = frames.mount(width = 1440, height = 1600) { discoverPage(books = picks(2)) }
            awaitFrame()
            frame.findAll(".scroll-row-arrows").size shouldBe 0
            frame.findAll(".disc-picks .disc-card").size shouldBeGreaterThan 0
        }
    })

/** Waits until [element]'s [name] attribute is [value] (null: absent), as a scroll lands a frame or two later. */
private suspend fun awaitAttribute(
    element: HTMLElement,
    name: String,
    value: String?,
) {
    withTimeout(ATTRIBUTE_TIMEOUT_MS) {
        while (element.getAttribute(name) != value) delay(POLL_MS)
    }
}

private const val ATTRIBUTE_TIMEOUT_MS = 2_000L
private const val POLL_MS = 10L
