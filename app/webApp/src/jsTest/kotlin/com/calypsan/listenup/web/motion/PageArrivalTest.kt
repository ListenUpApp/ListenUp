package com.calypsan.listenup.web.motion

import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.animatedProperties
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.durationOf
import com.calypsan.listenup.web.motions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * What a page change looks like: the region fades (opacity only, ever), and the page's own content
 * rises 8px inside it.
 *
 * ⛔ The region is the hero flight's measuring frame; a transform on it would skew every
 * destination rect. That is why the rise lives on `.page-body` and these specs check both halves.
 */
class PageArrivalTest :
    FunSpec({
        val frames = ViewportFrames()
        val page = mutableStateOf("library")

        afterTest {
            frames.disposeAll()
            page.value = "library"
            reducedMotionOverride = null
        }

        fun mount(): ViewportFrame =
            frames.mount(1280, 800) {
                InShell {
                    PageArrival(page.value) { Div { Text(page.value) } }
                }
            }

        test("the first page paints without motion") {
            val frame = mount()
            awaitFrame()

            frame.find(".shell-main").motions().size shouldBe 0
            frame.find(".page-body").motions().size shouldBe 0
        }

        test("a new page fades the region and settles its content") {
            val frame = mount()
            awaitFrame()

            page.value = "settings"
            awaitFrame()

            val fade = frame.find(".shell-main").motions().single()
            animatedProperties(fade) shouldBe setOf("opacity")
            durationOf(fade) shouldBe MotionToken.QUICK.millis

            val rise = frame.find(".page-body").motions().single()
            animatedProperties(rise) shouldBe setOf("transform")
            durationOf(rise) shouldBe MotionToken.MOVE.millis
        }

        test("the same page rearranging itself does not move") {
            val frame = mount()
            awaitFrame()

            page.value = "library"
            awaitFrame()

            frame.find(".page-body").motions().size shouldBe 0
        }

        test("under reduced motion a new page simply appears") {
            reducedMotionOverride = true
            val frame = mount()
            awaitFrame()

            page.value = "settings"
            awaitFrame()

            frame.find(".shell-main").motions().size shouldBe 0
            frame.find(".page-body").motions().size shouldBe 0
        }
    })
