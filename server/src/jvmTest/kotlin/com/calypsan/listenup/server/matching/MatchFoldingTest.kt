package com.calypsan.listenup.server.matching

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** What "the same name" means to Find, Review and Apply: case and accents set aside, other scripts left alone. */
class MatchFoldingTest :
    FunSpec({
        mapOf(
            "Brontë" to "bronte",
            "Stanisław Lem" to "stanislaw lem",
            "Æsop" to "aesop",
            "Straße" to "strasse",
            "e\u0301" to "e",
            "Bronte\u0308" to "bronte",
            "İstanbul" to "istanbul",
            "東野 圭吾" to "東野 圭吾",
            "ASCII only" to "ascii only",
        ).forEach { (input, folded) ->
            test("'$input' folds to '$folded'") {
                input.foldForMatching() shouldBe folded
            }
        }
    })
