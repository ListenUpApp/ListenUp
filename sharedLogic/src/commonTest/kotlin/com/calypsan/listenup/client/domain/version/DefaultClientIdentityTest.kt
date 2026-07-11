package com.calypsan.listenup.client.domain.version

import com.calypsan.listenup.api.EXPECTED_API_VERSION
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class DefaultClientIdentityTest :
    FunSpec({
        // Assert the SHAPE of the build-injected version, not a literal (see ServerIdentityTest):
        // pinning "0.6.0" would break on the next VERSION bump. "0.0.1" is the generator's fallback.
        test("version is build-injected from the repo-root VERSION file (not the stale default)") {
            DefaultClientIdentity.version shouldNotBe "0.0.1"
            DefaultClientIdentity.version shouldMatch Regex("""^\d+\.\d+\.\d+.*$""")
        }

        test("apiVersion reads the shared contract constant") {
            DefaultClientIdentity.apiVersion shouldBe EXPECTED_API_VERSION
        }
    })
