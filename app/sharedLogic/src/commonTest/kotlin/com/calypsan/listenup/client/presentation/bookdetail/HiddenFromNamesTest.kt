package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The "Hidden from" name list stops after three names, then counts the rest (spec §7). */
class HiddenFromNamesTest :
    FunSpec({
        val five = listOf("Alice", "Ben", "Dev", "Hana", "Zoe")

        test("three or fewer names are all shown") {
            HiddenFromNames.summarize(listOf("Alice", "Ben"), expanded = false) shouldBe
                HiddenFromSummary(listOf("Alice", "Ben"), othersCount = 0)
            HiddenFromNames.summarize(five.take(3), expanded = false) shouldBe
                HiddenFromSummary(five.take(3), othersCount = 0)
        }

        test("a longer list shows the first three and counts the others") {
            HiddenFromNames.summarize(five, expanded = false) shouldBe
                HiddenFromSummary(listOf("Alice", "Ben", "Dev"), othersCount = 2)
        }

        test("expanded shows every name") {
            HiddenFromNames.summarize(five, expanded = true) shouldBe HiddenFromSummary(five, othersCount = 0)
        }

        test("Show all is offered only while names are hidden") {
            HiddenFromNames.canExpand(five, expanded = false) shouldBe true
            HiddenFromNames.canExpand(five, expanded = true) shouldBe false
            HiddenFromNames.canExpand(five.take(3), expanded = false) shouldBe false
        }
    })
