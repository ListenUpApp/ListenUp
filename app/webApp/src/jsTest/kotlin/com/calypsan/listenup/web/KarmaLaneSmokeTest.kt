package com.calypsan.listenup.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Proves the karma lane itself works — a browser starts, Kotest's js entry point is
 * generated, and a spec is discovered and reported. Deliberately asserts something
 * trivial: when this fails, the lane is broken, not the code under test.
 */
class KarmaLaneSmokeTest :
    FunSpec({
        test("the karma lane runs a spec in a real browser") {
            js("typeof window").toString() shouldBe "object"
        }
    })
