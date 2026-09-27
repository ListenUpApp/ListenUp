package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Navigating from a screen that may be a list pane. On a compact window the screen is always the
 * top of the stack, so both helpers must reduce to the plain push and pop they replace.
 */
class PaneNavigationTest :
    FunSpec({
        test("from the top of the stack, navigateFrom is a plain push") {
            val stack = mutableListOf<NavKey>(Shell, SeriesDetail("s1"))

            stack.navigateFrom(SeriesDetail("s1"), BookDetail("b1"))

            stack shouldBe listOf(Shell, SeriesDetail("s1"), BookDetail("b1"))
        }

        test("from a list pane, the book beside it is replaced, not stacked") {
            val stack = mutableListOf<NavKey>(Shell, SeriesDetail("s1"), BookDetail("b1"))

            stack.navigateFrom(SeriesDetail("s1"), BookDetail("b2"))

            stack shouldBe listOf(Shell, SeriesDetail("s1"), BookDetail("b2"))
        }

        test("from a screen no longer on the stack, navigateFrom still pushes") {
            val stack = mutableListOf<NavKey>(Shell)

            stack.navigateFrom(SeriesDetail("gone"), BookDetail("b1"))

            stack shouldBe listOf(Shell, BookDetail("b1"))
        }

        test("from the top of the stack, popFrom removes exactly that screen") {
            val stack = mutableListOf<NavKey>(Shell, ContributorDetail("c1"))

            stack.popFrom(ContributorDetail("c1"))

            stack shouldBe listOf(Shell)
        }

        test("the list pane's Back leaves the list and closes the book beside it") {
            val stack = mutableListOf<NavKey>(Shell, ContributorDetail("c1"), BookDetail("b1"))

            stack.popFrom(ContributorDetail("c1"))

            stack shouldBe listOf(Shell)
        }

        test("popFrom acts on the topmost copy of a screen that appears twice") {
            val stack = mutableListOf<NavKey>(Shell, SeriesDetail("s1"), BookDetail("b1"), SeriesDetail("s1"))

            stack.popFrom(SeriesDetail("s1"))

            stack shouldBe listOf(Shell, SeriesDetail("s1"), BookDetail("b1"))
        }

        test("from a screen no longer on the stack, popFrom still pops the top") {
            val stack = mutableListOf<NavKey>(Shell, BookDetail("b1"))

            stack.popFrom(SeriesDetail("gone"))

            stack shouldBe listOf(Shell)
        }
    })
