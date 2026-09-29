package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Aside
import org.w3c.dom.HTMLElement

/**
 * What the progress bars and the sidebar animate — against the real sheet.
 *
 * A `width` transition lays the page out again on every frame it runs; a `transform` is composited.
 * Every progress bar is the shared `ProgressBar`, whose fill is scaled with `scaleX`, and the sidebar collapse stopped animating at
 * all, since its labels snap out on the first frame and every card in the grid beside it re-flowed
 * sixty times a second for nothing.
 */
class CompositedMotionTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun computed(
            host: HTMLElement,
            selector: String,
            property: String,
        ): String = window.getComputedStyle(host.querySelector(selector)!!).getPropertyValue(property)

        ProgressLook.entries.forEach { look ->
            test("a $look progress fill animates a transform, scaled from its left edge") {
                val host = mounts.mount { WebAppSurface { ProgressBar(value = 0.5f, label = "Progress", look = look) } }

                computed(host, ".progress-fill", "transition-property") shouldBe "transform"
                computed(host, ".progress-fill", "transform-origin").split(" ").first() shouldBe "0px"
            }
        }

        test("the sidebar collapse does not animate its width") {
            val host = mounts.mount { WebAppSurface { Aside(attrs = { classes("sidebar") }) } }

            computed(host, ".sidebar", "transition-property") shouldNotContain "width"
        }
    })
