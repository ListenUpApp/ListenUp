package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The sort row the Library, Series and Contributors lists share.
 *
 * ⛔ Each page had its own copy made of `<div onClick>`s, and the direction was a bare "↑"/"↓" —
 * unreachable by keyboard and, to a screen reader, an unlabelled arrow character.
 */
class SortControlTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(
            ascending: Boolean = true,
            onSelect: (String) -> Unit = {},
            onToggle: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                SortControl(
                    options = listOf("title", "added"),
                    active = "added",
                    labelOf = { it.replaceFirstChar(Char::uppercase) },
                    ascending = ascending,
                    onSelect = onSelect,
                    onToggleDirection = onToggle,
                )
            }

        test("the options are buttons, and only the active one is pressed") {
            val host = mount()

            val options = host.querySelectorAll(".lib-sort-option").asList().filterIsInstance<HTMLElement>()
            options.map { it.tagName } shouldBe listOf("BUTTON", "BUTTON")
            options.map { it.getAttribute("type") } shouldBe listOf("button", "button")
            options.map { it.getAttribute("aria-pressed") } shouldBe listOf("false", "true")
        }

        test("the row is announced as what it sorts by") {
            val host = mount()

            val group = host.querySelector(".lib-sort") as HTMLElement
            group.getAttribute("role") shouldBe "group"
            group.getAttribute("aria-label") shouldBe "Sort by"
        }

        test("choosing an option reports it") {
            var chosen: String? = null
            val host = mount(onSelect = { chosen = it })

            (host.querySelectorAll(".lib-sort-option").item(0) as HTMLElement).click()

            chosen shouldBe "title"
        }

        test("the direction is a button whose name says which way the list runs") {
            val ascending = mount(ascending = true)
            val descending = mount(ascending = false)

            val up = ascending.querySelector(".lib-sort-direction") as HTMLElement
            up.tagName shouldBe "BUTTON"
            up.getAttribute("aria-label") shouldBe "Sort ascending"
            (descending.querySelector(".lib-sort-direction") as HTMLElement)
                .getAttribute("aria-label") shouldBe "Sort descending"
        }

        test("the arrow is a hidden icon, so the name is the only thing read out") {
            val host = mount()

            val direction = host.querySelector(".lib-sort-direction") as HTMLElement
            direction.querySelectorAll("svg[aria-hidden=true]").length shouldBe 1
            direction.textContent shouldBe ""
        }

        test("pressing the direction toggles it") {
            var toggles = 0
            val host = mount(onToggle = { toggles++ })

            (host.querySelector(".lib-sort-direction") as HTMLElement).click()

            toggles shouldBe 1
        }
    })
