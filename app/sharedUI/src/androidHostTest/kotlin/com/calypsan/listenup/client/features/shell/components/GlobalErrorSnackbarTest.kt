package com.calypsan.listenup.client.features.shell.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Pins the honest surfacing of [com.calypsan.listenup.api.error.AuthError.RateLimited]'s
 * `retryAfterSeconds` in the global snackbar message — the value its contract KDoc says clients
 * should show but which was previously surfaced nowhere.
 */
class GlobalErrorSnackbarTest :
    FunSpec({
        test("rate-limit snackbar message carries the concrete wait time") {
            val message = rateLimitedSnackbarMessage(retryAfterSeconds = 30)
            message shouldContain "30s"
            message shouldBe "Too many attempts. Try again in 30s."
        }
    })
