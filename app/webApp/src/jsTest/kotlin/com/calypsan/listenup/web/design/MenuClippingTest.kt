package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.TABLET
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.admin.AdminInboxPage
import com.calypsan.listenup.web.features.admin.inboxBook
import com.calypsan.listenup.web.features.admin.readyInbox
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement

private val ITEMS =
    listOf(
        MenuAction("Edit details", WebIcon.Pencil, onSelect = {}),
        MenuAction("Match details", WebIcon.Sparkles, onSelect = {}),
    )

private const val FRAME_HEIGHT = 640

/** The gap the menu keeps from its trigger, as it always has. */
private const val TRIGGER_GAP = 6.0

/**
 * A menu is never cut off by the box its trigger sits in.
 *
 * ⛔ The admin inbox's row menu opened INSIDE its "Waiting for review" card, and the card clips
 * (`overflow: clip`, so a sticky heading sticks to the page): with one book in the inbox, all that
 * showed of the menu was a sliver of its top edge, and neither item could be reached. Every card,
 * scroller or transformed box in the app does the same to anything hanging out of it, so the menu
 * is asserted visible from inside each kind of box — by the browser's own hit test, which is what
 * a pointer meets, not by a computed style that only says what was asked for.
 */
class MenuClippingTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        /** A box of the given style, with a trailing "more actions" at its very bottom. */
        fun boxed(style: String): @Composable () -> Unit =
            {
                Div(attrs = {
                    attr("style", "margin:24px; height:64px; display:flex; justify-content:flex-end; align-items:flex-end; $style")
                }) { ActionsMenu(ITEMS) }
            }

        val clippingBoxes =
            mapOf(
                "a Panel (overflow: clip)" to @Composable {
                    Panel(title = "Waiting for review") {
                        Div(attrs = { attr("style", "display:flex; justify-content:flex-end") }) { ActionsMenu(ITEMS) }
                    }
                },
                "an overflow: hidden card" to boxed("overflow:hidden"),
                "a scrolling box" to boxed("overflow:auto"),
                "a transformed box" to boxed("overflow:hidden; transform:translateZ(0)"),
                "a box with a higher-stacked neighbour below it" to @Composable {
                    boxed("position:relative; z-index:1")()
                    Div(attrs = { attr("style", "position:relative; z-index:50; height:200px; margin-top:-24px; background:red") })
                },
            )

        clippingBoxes.forEach { (name, content) ->
            test("a menu opened inside $name is wholly visible and takes the pointer") {
                val frame = frames.mount(TABLET, FRAME_HEIGHT) { content() }
                val trigger = frame.find(".menu-anchor button")
                val triggerTop = frame.rect(trigger).top

                trigger.click()
                awaitFrame()
                awaitFrame()

                frame.shouldShowWholly(frame.find(".menu"))
                // ⛔ Not by scrolling the box: focusing the first item used to scroll a clipping
                // box to reveal it, which dragged the row — trigger and all — out of sight.
                frame.rect(trigger).top shouldBe (triggerTop plusOrMinus 0.5)
            }
        }

        test("the inbox row menu is wholly visible below a lone book") {
            val frame =
                frames.mount(TABLET, FRAME_HEIGHT) {
                    AdminInboxPage(
                        state = readyInbox(books = listOf(inboxBook(title = "Other Worlds Than These"))),
                        onToggleBook = {},
                        onSelectAll = {},
                        onClearSelection = {},
                        onRelease = {},
                        onDismissIssue = {},
                        onClearError = {},
                        onClearReleaseResult = {},
                        onRetry = {},
                        onOpenAdmin = {},
                    )
                }

            frame.find(".inbox-book-row .menu-anchor button").click()
            awaitFrame()
            awaitFrame()

            val menu = frame.find(".menu")
            frame.shouldShowWholly(menu)
            frame.findAll(".menu-i").forEach { item -> frame.shouldTakePointerAtCentre(item) }
        }

        test("a menu with no room below its trigger opens above it") {
            val frame =
                frames.mount(TABLET, FRAME_HEIGHT) {
                    Div(attrs = {
                        attr("style", "height:${FRAME_HEIGHT}px; display:flex; align-items:flex-end; overflow:clip")
                    }) { ActionsMenu(ITEMS) }
                }

            frame.find(".menu-anchor button").click()
            awaitFrame()
            awaitFrame()

            val menu = frame.find(".menu")
            frame.shouldShowWholly(menu)
            frame.rect(menu).bottom shouldBeLessThanOrEqual frame.rect(frame.find(".menu-anchor button")).top
        }

        // The top layer is also where a modal puts the full-viewport backdrop that takes every press
        // behind it. A menu must not: the outside-press close would never fire, and nothing else on
        // the page could be pressed while a menu was open.
        test("with a menu open, a press elsewhere still lands on what is there") {
            val frame =
                frames.mount(TABLET, FRAME_HEIGHT) {
                    ActionsMenu(ITEMS)
                    Div(attrs = { attr("style", "position:fixed; right:0; bottom:0; width:80px; height:80px") }) {}
                }

            frame.find(".menu-anchor button").click()
            awaitFrame()

            val hit = frame.host.ownerDocument!!.elementFromPoint(TABLET - 40.0, FRAME_HEIGHT - 40.0)
            (hit?.getAttribute("style") ?: "") shouldBe "position:fixed; right:0; bottom:0; width:80px; height:80px"
        }

        test("an open menu stays with its trigger as the page scrolls") {
            val frame =
                frames.mount(TABLET, FRAME_HEIGHT) {
                    Div(attrs = { attr("style", "height:${FRAME_HEIGHT * 3}px; padding-top:200px") }) { ActionsMenu(ITEMS) }
                }

            frame.find(".menu-anchor button").click()
            awaitFrame()
            frame.window.scrollBy(0.0, 120.0)
            awaitFrame()
            awaitFrame()

            val trigger = frame.rect(frame.find(".menu-anchor button"))
            frame.rect(frame.find(".menu")).top shouldBe (trigger.bottom + TRIGGER_GAP plusOrMinus 1.0)
        }
    })

/** [element] lies inside the frame's viewport, and a pointer at its centre lands on it. */
private fun ViewportFrame.shouldShowWholly(element: HTMLElement) {
    val box = rect(element)
    box.top shouldBeGreaterThanOrEqual 0.0
    box.left shouldBeGreaterThanOrEqual 0.0
    box.bottom shouldBeLessThanOrEqual height.toDouble()
    box.right shouldBeLessThanOrEqual width.toDouble()
    shouldTakePointerAtCentre(element)
}

private fun ViewportFrame.shouldTakePointerAtCentre(element: HTMLElement) {
    val box = rect(element)
    val hit = host.ownerDocument!!.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
    withClue("a pointer at the centre of .${element.className} landed on ${hit?.tagName}.${hit?.className}") {
        (hit != null && (hit == element || element.contains(hit))) shouldBe true
    }
}
