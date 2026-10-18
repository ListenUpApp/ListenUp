package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/** A button's accessible name: its aria-label, or its words. */
private fun HTMLElement.buttonsNamed(name: String): List<HTMLElement> {
    val buttons = querySelectorAll("button")
    return (0 until buttons.length)
        .map { buttons.item(it) as HTMLElement }
        .filter { (it.getAttribute("aria-label") ?: it.textContent?.trim()) == name }
}

private fun HTMLElement.count(selector: String): Int = querySelectorAll(selector).length

private fun HTMLElement.press(key: String) {
    dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}

/** Spec §2.3 JustFinished and §8.2 on the web, with the half-star control Simon chose (Q2). */
class HomeFinishedTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun card(
            slot: HomeSlot.JustFinished = finishedSlot(),
            actions: HomeActions = HomeActions.None,
        ): HTMLElement = mounts.mount { HomeFinishedCard(slot.card, nowMs = BOARD_NOW, actions = actions, zone = TimeZone.UTC) }

        test("the card is a region named for what happened") {
            card().querySelector("section.home-finished")!!.getAttribute("aria-label") shouldBe "You finished a book"
        }

        test("it says when it was finished, how long and when it was read") {
            val host = card()
            host.textContent.orEmpty().contains("Finished today") shouldBe true
            host.textContent.orEmpty().contains("45h 30m · 6 Sep – 10 Oct") shouldBe true
        }

        test("an unknown start leaves the length alone") {
            card(finishedSlot(startedAtMs = null)).textContent.orEmpty().contains("45h 30m ·") shouldBe false
        }

        test("Play next book plays the next book through the play entry") {
            var played: String? = null
            card(actions = HomeActions.None.copy(onPlay = { played = it }))
                .buttonsNamed("Play next book: Words of Radiance")
                .single()
                .click()
            played shouldBe "wor"
        }

        test("dismiss calls the session's action with the finished book") {
            var dismissed: String? = null
            card(actions = HomeActions.None.copy(onDismissFinished = { dismissed = it }))
                .buttonsNamed("Dismiss on all your devices")
                .single()
                .click()
            dismissed shouldBe "wok"
        }

        test("the rating is the shared half-star control, labelled, and committing it rates") {
            var rated: Pair<String, Int>? = null
            val host = card(actions = HomeActions.None.copy(onRateFinished = { id, h -> rated = id to h }))
            val slider = host.querySelector("[role=slider]") as HTMLElement
            slider.getAttribute("aria-label") shouldBe "How was it?"
            slider.press("End")
            rated shouldBe ("wok" to 10)
        }

        test("the finishers line names two and counts the rest, as a control into the readers") {
            var readers: String? = null
            val host =
                card(finishedSlot(names = listOf("Maya", "Tom"), more = 3), HomeActions.None.copy(onOpenReaders = { readers = it }))
            val link = host.querySelector(".home-finished-others") as HTMLElement
            link.textContent shouldBe "Maya, Tom and 3 more finished it too"
            link.click()
            readers shouldBe "wok"
        }

        test("no names, no finishers line") {
            card(finishedSlot(names = emptyList(), more = 2)).count(".home-finished-others") shouldBe 0
        }

        test("no next book: no primary button") {
            card(finishedSlot(next = null)).count(".home-finished-play") shouldBe 0
        }

        test("cover and title are one control into the book, never nested") {
            var opened: String? = null
            val host = card(actions = HomeActions.None.copy(onOpenBook = { opened = it }))
            host.count(".home-finished-id") shouldBe 1
            host.querySelectorAll(".home-finished-id button, .home-finished-id a").length shouldBe 0
            (host.querySelector(".home-finished-id") as HTMLElement).click()
            opened shouldBe "wok"
        }
    })
