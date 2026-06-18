package com.calypsan.listenup.client.features.settings

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class LicenseFamilyColorTest :
    FunSpec({
        test("known SPDX families get stable distinct colors") {
            licenseFamilyColor("Apache-2.0") shouldNotBe licenseFamilyColor("MIT")
            licenseFamilyColor("Apache-2.0") shouldBe licenseFamilyColor("Apache-2.0")
        }
        test("unknown license falls back to the neutral color") {
            licenseFamilyColor("Totally-Unknown-1.0") shouldBe LICENSE_FALLBACK_COLOR
        }
    })
