package com.calypsan.listenup.web.motion

import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.motions
import com.calypsan.listenup.web.timingOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * A page's first screenful arrives in a quick sweep: 20 ms apart, never more than 200 ms in all,
 * and only for what the reader can see. Everything below the fold renders plainly.
 */
class StaggerTest :
    FunSpec({
        val attached = mutableListOf<HTMLElement>()

        afterTest {
            attached.forEach { it.remove() }
            attached.clear()
            forgetPageArrival()
            forgetHeroFlight()
            reducedMotionOverride = null
        }

        /** A fixed column of [count] 100px rows from the top of the viewport. */
        fun column(count: Int): HTMLElement {
            val container = document.createElement("div") as HTMLElement
            container.style.cssText = "position:fixed;top:0;left:0;width:300px"
            repeat(count) {
                val row = document.createElement("div") as HTMLElement
                row.style.height = "100px"
                container.appendChild(row)
            }
            document.body!!.appendChild(container)
            attached += container
            return container
        }

        fun HTMLElement.rows(): List<HTMLElement> = (0 until children.length).map { children.item(it) as HTMLElement }

        test("a short screenful steps 20 ms apart") {
            staggerDelays(5) shouldBe listOf(0.0, 20.0, 40.0, 60.0, 80.0)
        }

        test("a long screenful compresses so the last item starts by 200 ms") {
            val delays = staggerDelays(28)

            delays.last() shouldBeLessThanOrEqual 200.0
            delays.zipWithNext().all { (a, b) -> b > a } shouldBe true
        }

        test("only the items on screen stagger") {
            val container = column(40)

            staggerIn(container)

            val rows = container.rows()
            val onScreen = rows.filter(::isOnScreen)
            onScreen.size shouldBeGreaterThan 0
            onScreen.all { it.motions().size == 1 } shouldBe true
            rows.filterNot(::isOnScreen).all { it.motions().isEmpty() } shouldBe true
            onScreen.maxOf { timingOf(it.motions().single(), "delay").unsafeCast<Number>().toDouble() } shouldBeLessThanOrEqual 200.0
        }

        test("a virtual list's card staggers; its wrapper and spacers do not") {
            val container = document.createElement("div") as HTMLElement
            container.style.cssText = "position:fixed;top:0;left:0;width:300px"
            container.innerHTML =
                """<div class="vl-spacer" style="height:10px"></div>""" +
                """<div class="vl-item" style="display:contents"><div class="card" style="height:100px"></div></div>"""
            document.body!!.appendChild(container)
            attached += container

            staggerIn(container)

            (container.querySelector(".card") as HTMLElement).motions().size shouldBe 1
            (container.querySelector(".vl-spacer") as HTMLElement).motions().size shouldBe 0
            (container.querySelector(".vl-item") as HTMLElement).motions().size shouldBe 0
        }

        test("an item a cover is flying into is left to the flight") {
            val container = column(3)
            val target = container.rows().first()
            recordHeroOrigin("book-s", CoverSurface.HERO, container.rows().last())
            flyHeroInto("book-s", CoverSurface.GRID, target)

            staggerIn(container)

            target.motions().size shouldBe 0
            container.rows()[1].motions().size shouldBe 1
        }

        test("a container staggers only while a page is arriving") {
            val early = column(3)
            staggerOnArrival(early)
            awaitFrame()
            early
                .rows()
                .first()
                .motions()
                .size shouldBe 0

            markPageArrival()
            val arriving = column(3)
            staggerOnArrival(arriving)
            awaitFrame()
            arriving
                .rows()
                .first()
                .motions()
                .size shouldBe 1
        }

        test("a page arrival long past staggers nothing") {
            markPageArrival(now = 0.0)

            isArriving(now = 10_000.0) shouldBe false
        }
    })
