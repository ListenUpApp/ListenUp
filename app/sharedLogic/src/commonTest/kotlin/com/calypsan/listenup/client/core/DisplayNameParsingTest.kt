package com.calypsan.listenup.client.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DisplayNameParsingTest :
    FunSpec({
        test("splitDisplayName: two tokens → first / last") {
            splitDisplayName("Ada Lovelace") shouldBe ("Ada" to "Lovelace")
        }
        test("splitDisplayName: single token → first only") {
            splitDisplayName("Ada") shouldBe ("Ada" to "")
        }
        test("splitDisplayName: 3+ tokens → first / remainder") {
            splitDisplayName("Ada B Lovelace") shouldBe ("Ada" to "B Lovelace")
        }
        test("splitDisplayName: blank → empty pair") {
            splitDisplayName("   ") shouldBe ("" to "")
        }
        test("resolveNameFields: stored non-blank names win (no override)") {
            resolveNameFields("Ignored Joined", "Grace", "Hopper") shouldBe ("Grace" to "Hopper")
        }
        test("resolveNameFields: null stored names derive from displayName") {
            resolveNameFields("Ada Lovelace", null, null) shouldBe ("Ada" to "Lovelace")
        }
        test("resolveNameFields: blank stored names derive from displayName") {
            resolveNameFields("Ada Lovelace", "", "  ") shouldBe ("Ada" to "Lovelace")
        }
        test("resolveNameFields: partial stored (first only) is kept as-is") {
            resolveNameFields("Ada Lovelace", "Ada", null) shouldBe ("Ada" to "")
        }
    })
