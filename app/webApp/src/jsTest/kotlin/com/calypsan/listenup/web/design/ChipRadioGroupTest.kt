package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private val PERIODS = listOf("Week", "Month", "Year", "All time").map { ChipOption(it, it) }

/**
 * One keyboard model for every single choice drawn as chips: a radiogroup of radios, one tab stop on
 * the checked one, and arrows that move the selection with the focus, wrapping at the ends.
 */
class ChipRadioGroupTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        @Composable
        fun Chips(onPick: (String) -> Unit = {}) {
            var selected by remember { mutableStateOf("Week") }
            ChipRadioGroup("Leaderboard period", PERIODS, selected, {
                selected = it
                onPick(it)
            })
        }

        fun radios(host: HTMLElement) = host.querySelectorAll("[role=radio]").asList().map { it as HTMLElement }

        test("a radiogroup of radios, the checked one alone in the tab order") {
            val host = mounts.mount { Chips() }
            val group = host.querySelector("[role=radiogroup]") as HTMLElement
            group.getAttribute("aria-label") shouldBe "Leaderboard period"
            radios(host).map { it.getAttribute("aria-checked") } shouldBe listOf("true", "false", "false", "false")
            radios(host).map { it.tabIndex } shouldBe listOf(0, -1, -1, -1)
        }

        test("an arrow moves the selection and the focus, and wraps") {
            val host = mounts.mount { Chips() }
            val first = radios(host).first()
            first.focus()
            first.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowLeft", bubbles = true)))
            awaitFrame()
            radios(host).last().getAttribute("aria-checked") shouldBe "true"
            document.activeElement shouldBe radios(host).last()
        }

        test("ArrowDown works like ArrowRight, as in any radio group") {
            val host = mounts.mount { Chips() }
            val first = radios(host).first()
            first.focus()
            first.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowDown", bubbles = true)))
            awaitFrame()
            radios(host)[1].getAttribute("aria-checked") shouldBe "true"
            document.activeElement shouldBe radios(host)[1]
        }

        test("a click selects, and selecting the selected one reports nothing new") {
            val picked = mutableListOf<String>()
            val host = mounts.mount { Chips(onPick = { picked += it }) }
            radios(host)[2].click()
            awaitFrame()
            radios(host)[2].click()
            awaitFrame()
            picked shouldBe listOf("Year")
            radios(host)[2].getAttribute("aria-checked") shouldBe "true"
        }
    })
