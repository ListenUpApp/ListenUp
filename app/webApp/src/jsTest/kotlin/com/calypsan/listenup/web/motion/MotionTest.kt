package com.calypsan.listenup.web.motion

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.animatedProperties
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.durationOf
import com.calypsan.listenup.web.motions
import com.calypsan.listenup.web.timingOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Aside
import org.w3c.dom.HTMLElement

/**
 * The web client's one motion module, against the real sheet.
 *
 * What it pins: the tokens and the CSS custom properties are one source; the helper can only say
 * opacity and transform; reduced motion starts nothing; the viewport check is honest. And — folded
 * in from the old `CompositedMotionTest` — that the CSS transitions which predate the helper animate
 * composited properties too.
 */
class MotionTest :
    FunSpec({
        val mounts = MountRegistry()
        val attached = mutableListOf<HTMLElement>()

        fun box(
            top: Int = 100,
            left: Int = 100,
            size: Int = 100,
        ): HTMLElement {
            val element = document.createElement("div") as HTMLElement
            element.style.cssText = "position:fixed;top:${top}px;left:${left}px;width:${size}px;height:${size}px"
            document.body!!.appendChild(element)
            attached += element
            return element
        }

        afterTest {
            mounts.disposeAll()
            attached.forEach { it.remove() }
            attached.clear()
            reducedMotionOverride = null
        }

        fun computed(
            host: HTMLElement,
            selector: String,
            property: String,
        ): String = window.getComputedStyle(host.querySelector(selector)!!).getPropertyValue(property)

        test("every motion token is the CSS custom property of the same name") {
            val root = window.getComputedStyle(document.documentElement!!)
            MotionToken.entries.forEach { token ->
                root.getPropertyValue(token.cssProperty).trim() shouldBe "${token.millis}ms"
            }
            root.getPropertyValue(MOTION_EASING_PROPERTY).trim() shouldBe MOTION_EASING
        }

        test("a composited motion runs for its token on the house curve") {
            val element = box()
            animateComposited(element, listOf(Keyframe.opacity(0.0), Keyframe.opacity(1.0)), MotionToken.ENTER)

            val animation = element.motions().single()
            durationOf(animation) shouldBe MotionToken.ENTER.millis
            timingOf(animation, "easing").unsafeCast<String>() shouldBe MOTION_EASING
        }

        test("it hands the browser nothing but opacity and transform") {
            val element = box()
            animateComposited(
                element,
                listOf(Keyframe.of(opacity = 0.0, transform = "translateY(8px)"), Keyframe.of(opacity = 1.0, transform = "none")),
                MotionToken.MOVE,
                transformOrigin = "0 0",
            )

            // transform-origin rides every frame unchanged: it positions the transform, it is not animated.
            (animatedProperties(element.motions().single()) - setOf("opacity", "transform", "transformOrigin")) shouldBe emptySet()
        }

        test("a delayed motion holds its first frame until it starts") {
            val element = box()
            animateComposited(
                element,
                listOf(Keyframe.opacity(0.0), Keyframe.opacity(1.0)),
                MotionToken.ENTER,
                delayMs = 60.0,
                fill = MotionFill.BACKWARDS,
            )

            val animation = element.motions().single()
            timingOf(animation, "delay").unsafeCast<Number>().toInt() shouldBe 60
            timingOf(animation, "fill").unsafeCast<String>() shouldBe "backwards"
        }

        test("under reduced motion it starts nothing and reports itself finished") {
            reducedMotionOverride = true
            val element = box()
            var finished = false

            animateComposited(element, listOf(Keyframe.opacity(0.0), Keyframe.opacity(1.0)), MotionToken.ENTER)
                .whenFinished { finished = true }

            element.motions().size shouldBe 0
            finished shouldBe true
        }

        test("isOnScreen is true only for a box the reader can see") {
            isOnScreen(box(top = 100)) shouldBe true
            isOnScreen(box(top = window.innerHeight + 50)) shouldBe false
            isOnScreen(box(top = -500)) shouldBe false
            isOnScreen(box(size = 0)) shouldBe false
        }

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
