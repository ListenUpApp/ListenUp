package com.calypsan.listenup.server.plugins

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Health probes arrive every few seconds; logging each one buried every real request in the log. */
class QuietRequestPathsTest :
    FunSpec({
        test("a health probe is not written to the access log") {
            isQuietRequestPath("/healthz") shouldBe true
        }

        test("every other request is") {
            isQuietRequestPath("/api/rpc/authed") shouldBe false
            isQuietRequestPath("/healthz/extra") shouldBe false
        }
    })
