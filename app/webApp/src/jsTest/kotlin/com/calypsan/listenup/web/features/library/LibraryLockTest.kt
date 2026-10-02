package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.LocalRestrictedBookIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
                        BookCard(book = contractBook("b1", "Dune"), progress = 0f, onOpen = {}, selecting = selecting)
                    }
                }.querySelector(".lib-card") as HTMLElement

        test("a restricted book's card carries the lock inside its cover") {
            (card(setOf("b1")).querySelector(".lib-cover > .lu-lock") != null) shouldBe true
        }

        test("an unrestricted card carries none") {
            (card(emptySet()).querySelector(".lu-lock") == null) shouldBe true
        }

        test("the lock does not change the card's height") {
            card(setOf("b1")).offsetHeight shouldBe card(emptySet()).offsetHeight
        }

        test("while selecting, the lock steps clear of the tick") {
            (card(setOf("b1"), selecting = true).querySelector(".lu-lock.shifted") != null) shouldBe true
        }
    })
