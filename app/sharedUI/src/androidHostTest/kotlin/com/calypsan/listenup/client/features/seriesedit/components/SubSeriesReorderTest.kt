package com.calypsan.listenup.client.features.seriesedit.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Three 50-tall rows with a 10 gap between them. */
private val rows =
    listOf(
        RowSpan("a", top = 0f, bottom = 50f),
        RowSpan("b", top = 60f, bottom = 110f),
        RowSpan("c", top = 120f, bottom = 170f),
    )

/** Where a dragged sub-series row lands as its centre moves down the list. */
class SubSeriesReorderTest :
    FunSpec({

        test("a centre inside a row is that row's slot") {
            slotUnder(rows, 25f) shouldBe 0
            slotUnder(rows, 85f) shouldBe 1
            slotUnder(rows, 169f) shouldBe 2
        }

        test("above the first row is the first slot, below the last is the last") {
            slotUnder(rows, -40f) shouldBe 0
            slotUnder(rows, 400f) shouldBe 2
        }

        test("the gap between rows keeps the row where it is") {
            slotUnder(rows, 55f) shouldBe null
        }

        test("an empty list has no slots") {
            slotUnder(emptyList(), 10f) shouldBe null
        }
    })
