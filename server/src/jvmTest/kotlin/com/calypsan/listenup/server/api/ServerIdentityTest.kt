package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.EXPECTED_API_VERSION
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerIdentityTest :
    FunSpec({
        test("VERSION is build-injected from the repo-root VERSION file") {
            ServerIdentity.VERSION shouldBe "0.6.0"
        }

        test("API_VERSION reads the shared contract constant") {
            ServerIdentity.API_VERSION shouldBe EXPECTED_API_VERSION
        }
    })
