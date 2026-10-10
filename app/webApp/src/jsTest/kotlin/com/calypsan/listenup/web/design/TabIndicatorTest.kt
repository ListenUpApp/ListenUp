package com.calypsan.listenup.web.design

import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.animatedProperties
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.durationOf
import com.calypsan.listenup.web.motion.MotionToken
import com.calypsan.listenup.web.motion.reducedMotionOverride
import com.calypsan.listenup.web.motions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement

/**
 * One underline that travels to the tab the reader picked, instead of one underline vanishing and
 * another appearing. It slides only for a change of tab — never when the strip first appears, and
 * never when a count changing a tab's width just moves it.
 */
class TabIndicatorTest :
    FunSpec({
        val mounts = MountRegistry()
        val active = mutableStateOf("overview")
        val chapters = mutableStateOf("44")

        afterTest {
            mounts.disposeAll()
            active.value = "overview"
            chapters.value = "44"
            reducedMotionOverride = null
        }

        fun strip(): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    Tabs(
                        items =
                            listOf(
                                TabItem("overview", "Overview"),
                                TabItem("chapters", "Chapters", count = chapters.value),
                                TabItem("files", "Files", count = "3"),
                            ),
                        active = active.value,
                        idBase = "t",
                        onSelect = { active.value = it },
                    )
                }
            }

        /**
         * The ink re-measures after the strip recomposes and moves on the recomposition that
         * follows: the frame in between still shows it under the old tab, which is where a slide
         * starts anyway.
         */
        suspend fun awaitInkMoved() = repeat(2) { awaitFrame() }

        fun HTMLElement.ink(): HTMLElement = querySelector(".tab-ink") as HTMLElement

        fun HTMLElement.tab(key: String): HTMLElement = querySelector("#t-tab-$key") as HTMLElement

        test("the ink sits under the active tab, and the tab's own underline steps aside") {
            val host = strip()
            awaitFrame()

            host.ink().classList.contains("is-placed") shouldBe true
            host.ink().getBoundingClientRect().left shouldBe (host.tab("overview").getBoundingClientRect().left plusOrMinus 0.5)
            host.ink().getBoundingClientRect().bottom shouldBe (host.tab("overview").getBoundingClientRect().bottom plusOrMinus 0.5)
            window.getComputedStyle(host.tab("overview")).getPropertyValue("border-bottom-color") shouldBe "rgba(0, 0, 0, 0)"
        }

        test("a strip that first appears does not slide its ink in") {
            val host = strip()
            awaitFrame()

            host.ink().motions().size shouldBe 0
        }

        test("picking a tab slides the ink there over MOVE") {
            val host = strip()
            awaitFrame()

            host.tab("files").click()
            awaitInkMoved()

            val slide = host.ink().motions().single()
            durationOf(slide) shouldBe MotionToken.MOVE.millis
            (animatedProperties(slide) - setOf("transform", "transformOrigin")) shouldBe emptySet()

            delay(400)
            host.ink().getBoundingClientRect().left shouldBe (host.tab("files").getBoundingClientRect().left plusOrMinus 0.5)
        }

        test("a count that changes a tab's width moves the ink without sliding it") {
            active.value = "files"
            val host = strip()
            awaitFrame()

            chapters.value = "1,244"
            awaitInkMoved()

            host.ink().motions().size shouldBe 0
            host.ink().getBoundingClientRect().left shouldBe (host.tab("files").getBoundingClientRect().left plusOrMinus 0.5)
        }

        test("under reduced motion the ink moves without sliding") {
            reducedMotionOverride = true
            val host = strip()
            awaitFrame()

            host.tab("chapters").click()
            awaitInkMoved()

            host.ink().motions().size shouldBe 0
            host.ink().getBoundingClientRect().left shouldBe (host.tab("chapters").getBoundingClientRect().left plusOrMinus 0.5)
        }
    })
