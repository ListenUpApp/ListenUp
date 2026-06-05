package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AbridgedTitleTest :
    FunSpec({
        test("(Unabridged) is stripped and abridged=false") {
            parseAbridgedFromTitle("The Way of Kings (Unabridged)") shouldBe ("The Way of Kings" to false)
        }
        test("(Abridged) is stripped and abridged=true") {
            parseAbridgedFromTitle("Short Tale (Abridged)") shouldBe ("Short Tale" to true)
        }
        test("dash- or colon-suffixed unabridged is stripped") {
            parseAbridgedFromTitle("Mistborn - Unabridged") shouldBe ("Mistborn" to false)
            parseAbridgedFromTitle("Elantris: Abridged") shouldBe ("Elantris" to true)
        }
        test("a plain title is returned unchanged, unabridged") {
            parseAbridgedFromTitle("Warbreaker") shouldBe ("Warbreaker" to false)
        }
        test("detection is case-insensitive") {
            parseAbridgedFromTitle("Foo (UNABRIDGED)") shouldBe ("Foo" to false)
        }
    })
