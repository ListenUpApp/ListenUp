package com.calypsan.listenup.client.features.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VolumeBoostPresetsTest :
    FunSpec({
        test("presets span the full boost range in 3 dB steps") {
            VolumeBoostPresets.presets shouldBe listOf(0f, 3f, 6f, 9f, 12f)
        }

        test("format returns the off label at the floor") {
            VolumeBoostPresets.format(0f, offLabel = "Off", dbLabel = "+9 dB") shouldBe "Off"
        }

        test("format returns the dB label above the floor") {
            VolumeBoostPresets.format(9f, offLabel = "Off", dbLabel = "+9 dB") shouldBe "+9 dB"
        }
    })
