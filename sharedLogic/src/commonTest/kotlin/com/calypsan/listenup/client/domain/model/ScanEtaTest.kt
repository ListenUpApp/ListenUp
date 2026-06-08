package com.calypsan.listenup.client.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ScanEtaTest :
    FunSpec({
        test("returns null before enough progress to estimate") {
            etaMinutes(elapsedMs = 1_000, fraction = 0.0f) shouldBe null
            etaMinutes(elapsedMs = 1_000, fraction = 0.01f) shouldBe null
        }
        test("estimates remaining minutes from elapsed and fraction, floored at 1") {
            etaMinutes(elapsedMs = 60_000, fraction = 0.5f) shouldBe 1
            etaMinutes(elapsedMs = 60_000, fraction = 0.25f) shouldBe 3
        }
    })
