package com.calypsan.listenup.client.features.seriesdetail.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Where a pinned group heading draws its text: below the status bar, never above its own slot, and
 * pushed out by the next heading without the two ever overlapping.
 */
class StickyHeadingShiftTest :
    FunSpec({
        val inset = 40
        val height = 60

        test("a heading well down the page draws in its own slot") {
            stickyHeadingShift(headingOffset = 300, nextHeadingOffset = null, headingHeight = height, topInset = inset) shouldBe 0
        }

        test("a heading sliding under the status bar keeps its text just below it") {
            stickyHeadingShift(headingOffset = 15, nextHeadingOffset = null, headingHeight = height, topInset = inset) shouldBe 25
        }

        test("a pinned heading draws its text the full status bar down") {
            stickyHeadingShift(headingOffset = 0, nextHeadingOffset = 900, headingHeight = height, topInset = inset) shouldBe inset
        }

        test("the next heading arriving lifts the pinned one's text so it ends where the next begins") {
            val next = 80
            val shift = stickyHeadingShift(headingOffset = 0, nextHeadingOffset = next, headingHeight = height, topInset = inset)

            shift shouldBe 20
            shift + height shouldBe next
        }

        test("while being pushed out, the pinned heading's text ends exactly at the status bar") {
            val next = 30
            val placed = next - height
            val shift = stickyHeadingShift(headingOffset = placed, nextHeadingOffset = next, headingHeight = height, topInset = inset)

            placed + shift + height shouldBe inset
        }

        test("with no status bar to clear, nothing moves") {
            stickyHeadingShift(headingOffset = 0, nextHeadingOffset = null, headingHeight = height, topInset = 0) shouldBe 0
        }
    })
