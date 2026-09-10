package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * The Edit affordance in the book header.
 *
 * It is an icon-only square beside Play, so its accessible name lives in `aria-label` — these pin
 * that a control whose visual label is a pencil still says what it does to a screen reader, that
 * it stands exactly as tall as the Play button it sits beside, and that it still reports [onEdit]
 * like the labelled button it replaced.
 */
class BookDetailEditButtonTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun rendered(onEdit: () -> Unit = {}): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = readyBook(),
                    tab = "chapters",
                    onSelectTab = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onEdit = onEdit,
                )
            }

        test("edit is a pencil icon button that still says what it does") {
            val root = rendered()

            val button = root.querySelector("button[aria-label='Edit book']") as? HTMLButtonElement
            button.shouldNotBeNull()
            (button.querySelector("svg") != null) shouldBe true
        }

        test("edit stands exactly as tall as Play") {
            val root = rendered()

            val play = root.querySelector(".bd-actions .btn") as HTMLElement
            val edit = root.querySelector("button[aria-label='Edit book']") as HTMLElement

            window.getComputedStyle(edit).height shouldBe window.getComputedStyle(play).height
        }

        test("the pencil still reports onEdit") {
            var edited = false
            val root = rendered(onEdit = { edited = true })

            (root.querySelector("button[aria-label='Edit book']") as HTMLButtonElement).click()

            edited shouldBe true
        }
    })
