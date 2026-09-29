package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement

/**
 * Progress, drawn one way in three sizes.
 *
 * Seven bespoke bars each drew their own track and fill, most animating `width` and most silent to a
 * screen reader. This pins the shared one: a named progressbar with its value, scaled rather than
 * resized, and honest about work of unknown size.
 */
class ProgressBarTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("it is a progressbar, named, with its value between 0 and 100") {
            val host = mounts.mount { ProgressBar(value = 0.25f, label = "Organizing") }

            val bar = host.querySelector(".progress") as HTMLElement
            bar.getAttribute("role") shouldBe "progressbar"
            bar.getAttribute("aria-label") shouldBe "Organizing"
            bar.getAttribute("aria-valuemin") shouldBe "0"
            bar.getAttribute("aria-valuemax") shouldBe "100"
            bar.getAttribute("aria-valuenow") shouldBe "25"
        }

        test("the fill is scaled from the left, never resized") {
            val host = mounts.mount { ProgressBar(value = 0.25f, label = "Organizing") }

            val fill = host.querySelector(".progress-fill") as HTMLElement
            fill.style.transform shouldBe "scaleX(0.25)"
            fill.style.width shouldBe ""
        }

        test("work of unknown size is indeterminate: no value, and no bar pinned at zero") {
            val host = mounts.mount { ProgressBar(value = null, label = "Adding books") }

            val bar = host.querySelector(".progress") as HTMLElement
            bar.classList.contains("is-indeterminate") shouldBe true
            bar.hasAttribute("aria-valuenow") shouldBe false
            (host.querySelector(".progress-fill") as HTMLElement).getAttribute("style") shouldBe null
        }

        test("each look is its own class on the same component") {
            ProgressLook.entries.forEach { look ->
                val host = mounts.mount { ProgressBar(value = 0.5f, label = "Progress", look = look) }

                host.querySelector(".progress.progress-${look.name.lowercase()}") shouldBe host.querySelector(".progress")
            }
        }

        test("a bar that restates a number beside it is hidden, not read twice") {
            val host = mounts.mount { ProgressBar(value = 0.3f, decorative = true) }

            val bar = host.querySelector(".progress") as HTMLElement
            bar.getAttribute("aria-hidden") shouldBe "true"
            bar.hasAttribute("role") shouldBe false
        }

        test("a caption sits beside the bar, in words") {
            val host = mounts.mount { ProgressBar(value = 0.49f, label = "Listening progress", caption = "49% · 9h left") }

            (host.querySelector(".progress-line .progress-caption") as HTMLElement).textContent shouldBe "49% · 9h left"
        }
    })
