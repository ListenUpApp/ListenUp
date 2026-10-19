package com.calypsan.listenup.web.features.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The web's Discover copy says the same thing as every other platform's, in every case it can meet. */
class DiscoverWordsTest :
    FunSpec({
        test("the count line covers every mix of live and recent, and says nothing about nobody") {
            DiscoverWords.listeningCount(live = 2, recent = 3) shouldBe "2 listening now · 3 more in the last two weeks"
            DiscoverWords.listeningCount(live = 2, recent = 0) shouldBe "2 listening now"
            DiscoverWords.listeningCount(live = 0, recent = 3) shouldBe "3 in the last two weeks"
            DiscoverWords.listeningCount(live = 0, recent = 0) shouldBe null
        }

        test("a series line is numbered, bare, or standalone") {
            DiscoverWords.seriesLine("Mistborn", "1") shouldBe "Book 1 of Mistborn"
            DiscoverWords.seriesLine("Mistborn", null) shouldBe "Mistborn"
            DiscoverWords.seriesLine(null, null) shouldBe "Standalone"
        }

        test("a listener card names the book, then the person and when") {
            DiscoverWords.listenerLabel("Mistborn", "Maya Chen", isLive = true, whenText = "") shouldBe
                "Mistborn, Maya Chen is listening now"
            DiscoverWords.listenerLabel("Mistborn", "Maya Chen", isLive = false, whenText = "2 days ago") shouldBe
                "Mistborn, Maya Chen listened 2 days ago"
        }

        test("a stale roster says how old it is") {
            DiscoverWords.listeningAsOf("2 hours ago") shouldBe "As of 2 hours ago"
        }
    })
