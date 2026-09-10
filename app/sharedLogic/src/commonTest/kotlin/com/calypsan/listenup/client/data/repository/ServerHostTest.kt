package com.calypsan.listenup.client.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * [hostOfUrl] is the identity behind "is this the same server" — it decides whether a persisted
 * session is cleared on a URL change and whether a link-supplied address needs confirming. Two
 * different servers must never compare equal, and cosmetic differences (port, scheme, path,
 * letter case) must never compare different.
 */
class ServerHostTest :
    FunSpec({

        test("returns the plain host") {
            "https://example.com".hostOfUrl() shouldBe "example.com"
        }

        test("drops the port") {
            "http://example.com:8080".hostOfUrl() shouldBe "example.com"
        }

        test("treats https and http on the same host as the same host") {
            "https://example.com".hostOfUrl() shouldBe "http://example.com".hostOfUrl()
        }

        test("drops a trailing path") {
            "https://example.com/listenup".hostOfUrl() shouldBe "example.com"
        }

        test("keeps an IPv6 literal intact and drops its port") {
            "http://[::1]:8080".hostOfUrl() shouldBe "[::1]"
        }

        test("distinguishes two different IPv6 literals") {
            // Both pairs matter: a naive split on the first ':' collapses [::1] and [::2] to the same
            // "[" (the hole), while [::1] vs [fd00::2] only differed by accident of where ':' falls.
            "http://[::1]:8080".hostOfUrl() shouldNotBe "http://[::2]:8080".hostOfUrl()
            "http://[::1]:8080".hostOfUrl() shouldNotBe "http://[fd00::2]".hostOfUrl()
        }

        test("lower-cases the host because hostnames are case-insensitive") {
            "https://Example.com".hostOfUrl() shouldBe "example.com"
        }
    })
