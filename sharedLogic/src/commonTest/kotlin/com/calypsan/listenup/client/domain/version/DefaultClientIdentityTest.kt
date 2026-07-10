package com.calypsan.listenup.client.domain.version

import com.calypsan.listenup.api.EXPECTED_API_VERSION
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DefaultClientIdentityTest :
    FunSpec({
        test("version is build-injected from the repo-root VERSION file") {
            DefaultClientIdentity.version shouldBe "0.6.0"
        }

        test("apiVersion reads the shared contract constant") {
            DefaultClientIdentity.apiVersion shouldBe EXPECTED_API_VERSION
        }
    })
