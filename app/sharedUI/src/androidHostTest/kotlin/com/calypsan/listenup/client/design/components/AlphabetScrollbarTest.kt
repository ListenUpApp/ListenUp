package com.calypsan.listenup.client.design.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [calculateWeight]. The index itself comes from the shared letter sections and is
 * tested with them (`BookGridAlphabetIndexTest`).
 *
 * [calculateWeight] returns a fisheye weight based on distance from the
 * selected letter index, using constants from [AdaptiveScrollbarConfig].
 */
class AlphabetScrollbarTest :
    FunSpec({
        // =====================================================================
        // calculateWeight — fisheye distance weighting
        // =====================================================================

        test("no selection returns AT_REST weight") {
            calculateWeight(index = 3, selectedIndex = null) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_AT_REST
        }

        test("selected index (distance 0) returns WEIGHT_SELECTED") {
            calculateWeight(index = 5, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_SELECTED
        }

        test("distance 1 returns WEIGHT_DISTANCE_1") {
            calculateWeight(index = 4, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_1
            calculateWeight(index = 6, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_1
        }

        test("distance 2 returns WEIGHT_DISTANCE_2") {
            calculateWeight(index = 3, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_2
            calculateWeight(index = 7, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_2
        }

        test("distance 3 or more returns WEIGHT_DISTANT") {
            calculateWeight(index = 2, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANT
            calculateWeight(index = 0, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANT
            calculateWeight(index = 10, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANT
        }

        test("weight is symmetric around selectedIndex") {
            val selected = 4
            for (distance in 0..3) {
                val leftWeight = calculateWeight(index = selected - distance, selectedIndex = selected)
                val rightWeight = calculateWeight(index = selected + distance, selectedIndex = selected)
                leftWeight shouldBe rightWeight
            }
        }

        test("weights obey SELECTED > DISTANCE_1 > DISTANCE_2 > DISTANT") {
            val sel = 5
            val wSel = calculateWeight(sel, sel)
            val wD1 = calculateWeight(sel + 1, sel)
            val wD2 = calculateWeight(sel + 2, sel)
            val wDist = calculateWeight(sel + 3, sel)
            (wSel > wD1) shouldBe true
            (wD1 > wD2) shouldBe true
            (wD2 > wDist) shouldBe true
        }

        test("AT_REST weight is returned when selectedIndex is null regardless of index value") {
            for (i in 0..10) {
                calculateWeight(i, null) shouldBe AdaptiveScrollbarConfig.WEIGHT_AT_REST
            }
        }
    })
