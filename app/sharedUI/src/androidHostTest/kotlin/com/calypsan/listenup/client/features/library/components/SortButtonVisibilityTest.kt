package com.calypsan.listenup.client.features.library.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The Authors and Narrators tabs hide their sort button while the list is being scrolled down and
 * bring it back on the way up, at rest, or near the top. The rule is pure; the scroll history it
 * compares against is kept by a collector, never written from inside a derived state.
 */
class SortButtonVisibilityTest :
    FunSpec({
        test("near the top it shows, even mid-scroll downward") {
            sortButtonVisible(firstVisibleIndex = 0, offset = 49, previousOffset = 10, isScrollInProgress = true) shouldBe true
        }

        test("scrolling down past the top hides it") {
            sortButtonVisible(firstVisibleIndex = 3, offset = 120, previousOffset = 80, isScrollInProgress = true) shouldBe false
            sortButtonVisible(firstVisibleIndex = 0, offset = 50, previousOffset = 40, isScrollInProgress = true) shouldBe false
        }

        test("scrolling up brings it back") {
            sortButtonVisible(firstVisibleIndex = 3, offset = 60, previousOffset = 80, isScrollInProgress = true) shouldBe true
        }

        test("at rest it shows wherever the list is") {
            sortButtonVisible(firstVisibleIndex = 12, offset = 200, previousOffset = 100, isScrollInProgress = false) shouldBe true
        }
    })
