package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.EXPECTED_API_VERSION
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class ServerIdentityTest :
    FunSpec({
        // Assert the SHAPE of the build-injected version, not a literal — pinning "0.6.0" would go
        // red on the next VERSION bump (the Release workflow commits VERSION to main, then CI runs).
        // "0.0.1" is both the pre-injection hardcoded value and the generator's missing-file fallback,
        // so `shouldNotBe "0.0.1"` proves the VERSION file was found and injected.
        test("VERSION is build-injected from the repo-root VERSION file (not the stale default)") {
            ServerIdentity.VERSION shouldNotBe "0.0.1"
            ServerIdentity.VERSION shouldMatch Regex("""^\d+\.\d+\.\d+.*$""")
        }

        test("API_VERSION reads the shared contract constant") {
            ServerIdentity.API_VERSION shouldBe EXPECTED_API_VERSION
        }
    })
