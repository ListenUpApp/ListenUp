package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EmailTest :
    FunSpec({
        test("normalize lowercases, trims, and applies NFC") {
            Email.normalize("  Foo@Example.COM  ") shouldBe "foo@example.com"
            Email.normalize("Bar@Test.io") shouldBe "bar@test.io"
        }

        test("isLikelyEmail accepts user@host with at least one char on each side") {
            Email.isLikelyEmail("a@b") shouldBe true
            Email.isLikelyEmail("foo@bar.baz") shouldBe true
            Email.isLikelyEmail("@bar") shouldBe false
            Email.isLikelyEmail("foo@") shouldBe false
            Email.isLikelyEmail("no-at-sign") shouldBe false
            Email.isLikelyEmail("") shouldBe false
        }
    })
