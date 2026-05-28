package com.calypsan.listenup.server.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SearchHighlighterTest :
    FunSpec({
        val s = HL_START
        val e = HL_END
        test("wraps a matched token, preserving original case") {
            highlightMatches("The Way of Kings", "kings") shouldBe "The Way of ${s}Kings$e"
        }
        test("highlights multiple tokens") {
            highlightMatches("Brandon Sanderson", "sanderson brandon") shouldBe "${s}Brandon$e ${s}Sanderson$e"
        }
        test("returns null when no token matches") {
            highlightMatches("Mistborn", "dragon") shouldBe null
        }
        test("matches whole-word tokens only, not substrings") {
            highlightMatches("Kingsman", "kings") shouldBe null
        }
        test("null or blank field returns null") {
            highlightMatches(null, "x") shouldBe null
            highlightMatches("", "x") shouldBe null
        }
        test("blank query returns null") {
            highlightMatches("Anything", "   ") shouldBe null
        }
    })
