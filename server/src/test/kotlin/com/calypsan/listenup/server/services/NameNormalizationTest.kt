package com.calypsan.listenup.server.services

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NameNormalizationTest :
    FunSpec({

        test("lowercases, trims, and collapses internal whitespace") {
            normalizeForDedup("  Brandon   Sanderson  ") shouldBe "brandon sanderson"
        }

        test("a name differing only by case and spacing normalizes identically") {
            normalizeForDedup("J.R.R. Tolkien") shouldBe normalizeForDedup("j.r.r.   tolkien")
        }
    })
