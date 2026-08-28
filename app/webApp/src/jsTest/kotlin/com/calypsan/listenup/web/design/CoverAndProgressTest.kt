package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

class CoverAndProgressTest :
    FunSpec({

        test("a cover with artwork renders the image") {
            val host = mount { Cover(title = "The Institute", imageUrl = "/api/v1/books/x/cover") }

            host.querySelectorAll("img").length shouldBe 1
        }

        test("a cover without artwork falls back to a generated tile carrying the title") {
            val host = mount { Cover(title = "The Institute") }

            host.querySelectorAll("img").length shouldBe 0
            host.textContent!! shouldContain "The Institute"
        }

        test("the generated cover is stable for a given title") {
            // A cover that changes between renders reads as a bug even when nothing is wrong,
            // so the fallback must be derived, never random.
            val first = mount { Cover(title = "Project Hail Mary") }
            val second = mount { Cover(title = "Project Hail Mary") }

            val a = (first.querySelector("div") as HTMLElement).style.background
            val b = (second.querySelector("div") as HTMLElement).style.background
            a shouldBe b
            a shouldNotBe ""
        }

        test("different titles get different generated covers") {
            val a = mount { Cover(title = "The Institute") }
            val b = mount { Cover(title = "Project Hail Mary") }

            (a.querySelector("div") as HTMLElement).style.background shouldNotBe
                (b.querySelector("div") as HTMLElement).style.background
        }

        test("the alt text names the book rather than describing the picture") {
            val host = mount { Cover(title = "The Institute", imageUrl = "/cover.jpg") }

            (host.querySelector("img") as HTMLElement).getAttribute("alt") shouldBe "The Institute"
        }

        test("progress reports itself to assistive technology") {
            val host = mount { ProgressLine(percent = 49, remaining = "9h 18m left") }

            val bar = host.querySelector("[role=progressbar]") as HTMLElement
            bar.getAttribute("aria-valuenow") shouldBe "49"
        }

        test("a position past the end cannot overflow the track") {
            // Re-encoding a file can leave a stored position slightly beyond the new duration.
            val host = mount { ProgressLine(percent = 140, remaining = "0m left") }

            host.textContent!! shouldContain "100%"
            (host.querySelector("[role=progressbar]") as HTMLElement)
                .getAttribute("aria-valuenow") shouldBe "100"
        }

        test("a negative position clamps to zero rather than inverting the bar") {
            val host = mount { ProgressLine(percent = -5, remaining = "18h left") }

            host.textContent!! shouldContain "0%"
        }

        test("a cover too small to hold a title legibly shows the gradient alone") {
            // 44px search badges and 56px listener tiles both name the book beside the tile; a
            // title set inside one is clipped mid-word, which is noise next to an intact copy.
            val host = mount { Cover(title = "The Way of Kings", size = 56) }

            host.textContent!! shouldBe ""
        }

        // Audiobook artwork is 1:1. A portrait frame does not letterbox it — `object-fit: cover`
        // crops a third off the sides, title and author with it. Five call sites had done exactly
        // that, so these assert the SHAPE, not the numbers: geometry, in a real browser, over both
        // the inline-styled cover and the two frames the stylesheet draws.
        test("a cover is square whatever size it is asked for") {
            val host = mount { Cover(title = "The Institute", size = 140) }

            val box = (host.querySelector("div") as HTMLElement).getBoundingClientRect()
            box.height shouldBe box.width
        }

        test("the book-edit cover frame is square, so the preview is not a crop of its own") {
            val host = mount { Div(attrs = { classes("cover-art") }) {} }

            val box = (host.querySelector(".cover-art") as HTMLElement).getBoundingClientRect()
            box.height shouldBe box.width
        }

        test("the loading skeleton is square, so the card does not flinch when the image lands") {
            // Width comes from the parent, exactly as it does on Home; the aspect-ratio does the rest.
            val host =
                mount {
                    // `.luw` because the skeleton rule is scoped to the app surface, and this file's
                    // mount deliberately renders bare.
                    Div(attrs = {
                        classes("luw")
                        style { property("width", "${SKELETON_WIDTH}px") }
                    }) {
                        Div(attrs = { classes("home-card-cover-skel") }) {}
                    }
                }

            val box = (host.querySelector(".home-card-cover-skel") as HTMLElement).getBoundingClientRect()
            box.width shouldBe SKELETON_WIDTH.toDouble()
            box.height shouldBe box.width
        }
    })

/** An arbitrary parent width for the skeleton: it inherits width, so any number proves the ratio. */
private const val SKELETON_WIDTH = 160
