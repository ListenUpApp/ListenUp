package com.calypsan.listenup.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Proves the browser lane itself works — Playwright starts Chromium, Kotest's js entry point
 * is generated, and a spec is discovered and reported. Deliberately asserts something
 * trivial: when this fails, the lane is broken, not the code under test.
 */
class BrowserLaneSmokeTest :
    FunSpec({
        test("the browser lane runs a spec in a real browser") {
            js("typeof window").toString() shouldBe "object"
        }
    })
