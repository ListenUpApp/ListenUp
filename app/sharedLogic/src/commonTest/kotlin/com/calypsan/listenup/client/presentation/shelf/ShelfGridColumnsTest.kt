package com.calypsan.listenup.client.presentation.shelf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShelfGridColumnsTest :
    FunSpec({
        test("compact width yields 2 columns") {
            shelfGridColumns(ShelfGridWidth.Compact) shouldBe 2
        }
        test("medium width yields 4 columns") {
            shelfGridColumns(ShelfGridWidth.Medium) shouldBe 4
        }
        test("expanded width yields 6 columns") {
            shelfGridColumns(ShelfGridWidth.Expanded) shouldBe 6
        }
    })
