package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.LocalRestrictedBookIds
import com.calypsan.listenup.web.design.shouldSitInsideTopLeftOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * The library card wears the lock inside its cover — so it lifts with the cover on hover and never
 * changes the card's height (the virtualised grid depends on every card being the same height) —
 * and steps clear of the selection tick while selecting.
 */
class LibraryLockTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun card(
            restricted: Set<String>,
            selecting: Boolean = false,
        ): HTMLElement =
            mounts
                .mount {
                    CompositionLocalProvider(LocalRestrictedBookIds provides restricted) {
                        BookCard(book = contractBook("b1", "Dune"), status = null, onOpen = {}, selecting = selecting)
                    }
                }.querySelector(".lib-card") as HTMLElement

        test("a restricted book's card carries the lock inside its cover") {
            (card(setOf("b1")).querySelector(".lib-cover > .lu-lock") != null) shouldBe true
        }

        test("an unrestricted card carries none") {
            (card(emptySet()).querySelector(".lu-lock") == null) shouldBe true
        }

        // Absolute is what keeps the card's height: the virtualised grid's row arithmetic assumes
        // every card is the same height, and an in-flow lock would add its own to one of them.
        test("the lock is absolute, inside the cover at its top-left corner") {
            val card = card(setOf("b1"))
            val lock = card.querySelector(".lu-lock") as HTMLElement
            window.getComputedStyle(lock).position shouldBe "absolute"
            lock.shouldSitInsideTopLeftOf(card.querySelector(".lib-cover") as HTMLElement)
        }

        test("while selecting, the lock steps clear of the tick") {
            (card(setOf("b1"), selecting = true).querySelector(".lu-lock.shifted") != null) shouldBe true
        }

        // The tick comes first in the card and the cover after it, both positioned: without a
        // stacking order of its own the cover would paint over the tick it is meant to wear.
        test("while selecting, the tick is the topmost thing at its own centre") {
            val card = card(emptySet(), selecting = true)
            val tick = card.querySelector(".lib-tick") as HTMLElement
            tick.scrollIntoView()
            val box = tick.getBoundingClientRect()
            val hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
            (hit != null && (hit == tick || tick.contains(hit))) shouldBe true
        }
    })
