package com.calypsan.listenup.core

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The syntax floor for [ServerUrl]. A server URL is persisted and then used as the host every
 * authenticated request is sent to, so the type itself refuses the shapes that could carry a
 * credential in the URL or smuggle a control character past the transport layer. Test inputs
 * are assembled by concatenation so no credential-shaped literal appears in the source.
 */
class ServerUrlTest :
    FunSpec({

        test("accepts a plain https URL") {
            shouldNotThrowAny { ServerUrl("https://example.com") }
        }

        test("accepts an http URL with an explicit port") {
            shouldNotThrowAny { ServerUrl("http://example.com:8080") }
        }

        test("accepts an IPv4 literal host with a port") {
            shouldNotThrowAny { ServerUrl("http://192.168.1.5:8080") }
        }

        test("normalises a trailing slash away in value") {
            ServerUrl("https://example.com/").value shouldBe "https://example.com"
        }

        test("rejects userinfo in the authority") {
            val withUserinfo = "https://" + "u:p" + "@" + "example.com"
            shouldThrow<IllegalArgumentException> { ServerUrl(withUserinfo) }
        }

        test("rejects an embedded space") {
            shouldThrow<IllegalArgumentException> { ServerUrl("https://example.com/" + " " + "library") }
        }

        test("rejects an embedded newline") {
            shouldThrow<IllegalArgumentException> { ServerUrl("https://example.com" + "\n") }
        }

        test("rejects an embedded tab") {
            shouldThrow<IllegalArgumentException> { ServerUrl("https://example.com" + "\t" + "x") }
        }

        test("rejects a raw control character") {
            shouldThrow<IllegalArgumentException> { ServerUrl("https://example.com" + '') }
        }

        test("still rejects a blank value") {
            shouldThrow<IllegalArgumentException> { ServerUrl("") }
            shouldThrow<IllegalArgumentException> { ServerUrl("   ") }
        }

        test("still rejects a non-http scheme") {
            shouldThrow<IllegalArgumentException> { ServerUrl("ftp://example.com") }
        }
    })
