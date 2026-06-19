package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SortKeysTest :
    FunSpec({
        context("titleSort") {
            test("embedded sort title wins") {
                SortKeys.titleSort("The Way of Kings", embedded = "Way of Kings, The") shouldBe "Way of Kings, The"
            }
            test("strips a leading article when no embedded value") {
                SortKeys.titleSort("The Way of Kings", embedded = null) shouldBe "Way of Kings"
                SortKeys.titleSort("A Game of Thrones", embedded = null) shouldBe "Game of Thrones"
                SortKeys.titleSort("An Ember in the Ashes", embedded = null) shouldBe "Ember in the Ashes"
            }
            test("no article returns the title unchanged") {
                SortKeys.titleSort("Mistborn", embedded = null) shouldBe "Mistborn"
            }
            test("blank embedded falls back to article-strip") {
                SortKeys.titleSort("The Hobbit", embedded = "   ") shouldBe "Hobbit"
            }
            test("article match is case-insensitive and word-bounded") {
                SortKeys.titleSort("THE Stand", embedded = null) shouldBe "Stand"
                SortKeys.titleSort("Theology", embedded = null) shouldBe "Theology"
            }
        }
        context("sortName") {
            test("embedded sort name wins") {
                SortKeys.sortName("Brandon Sanderson", embedded = "Sanderson, Brandon") shouldBe "Sanderson, Brandon"
            }
            test("derives Last, First when no embedded value") {
                SortKeys.sortName("Brandon Sanderson", embedded = null) shouldBe "Sanderson, Brandon"
            }
            test("single token passes through") {
                SortKeys.sortName("Madonna", embedded = null) shouldBe "Madonna"
            }
            test("already comma form passes through") {
                SortKeys.sortName("Sanderson, Brandon", embedded = null) shouldBe "Sanderson, Brandon"
            }
            test("three-token name treats last token as surname") {
                SortKeys.sortName("Ursula K. LeGuin", embedded = null) shouldBe "LeGuin, Ursula K."
            }
            test("CJK name passes through unchanged") {
                SortKeys.sortName("村上春樹", embedded = null) shouldBe "村上春樹"
            }
            test("blank embedded falls back to derivation") {
                SortKeys.sortName("Brandon Sanderson", embedded = "  ") shouldBe "Sanderson, Brandon"
            }
        }
    })
