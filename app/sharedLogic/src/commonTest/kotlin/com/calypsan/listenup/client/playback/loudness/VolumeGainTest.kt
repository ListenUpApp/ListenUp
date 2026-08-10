package com.calypsan.listenup.client.playback.loudness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class VolumeGainTest :
    FunSpec({
        test("dbToLinear: 0 dB is unity, +6 dB ~ 2x, -6 dB ~ 0.5x") {
            VolumeGain.dbToLinear(0f) shouldBe (1.0f plusOrMinus 0.001f)
            VolumeGain.dbToLinear(6f) shouldBe (1.995f plusOrMinus 0.01f)
            VolumeGain.dbToLinear(-6f) shouldBe (0.501f plusOrMinus 0.01f)
        }

        test("effectiveGainDb: measured wins over book tag, both over zero") {
            VolumeGain.effectiveGainDb(measuredGainDb = -3f, bookNormalizationGainDb = 5f, userBoostDb = 2f) shouldBe
                (-1f plusOrMinus 0.001f)
            VolumeGain.effectiveGainDb(measuredGainDb = null, bookNormalizationGainDb = 5f, userBoostDb = 2f) shouldBe
                (7f plusOrMinus 0.001f)
            VolumeGain.effectiveGainDb(measuredGainDb = null, bookNormalizationGainDb = null, userBoostDb = 4f) shouldBe
                (4f plusOrMinus 0.001f)
        }

        test("applySample: scales then hard-clamps to +/-1") {
            val g = VolumeGain.dbToLinear(12f)
            VolumeGain.applySample(0.1f, g) shouldBe (0.398f plusOrMinus 0.01f)
            VolumeGain.applySample(0.5f, g) shouldBe (1.0f plusOrMinus 0.0f)
            VolumeGain.applySample(-0.5f, g) shouldBe (-1.0f plusOrMinus 0.0f)
        }

        test("boost range constants") {
            VolumeGain.MIN_BOOST_DB shouldBe 0f
            VolumeGain.MAX_BOOST_DB shouldBe 12f
        }
    })
