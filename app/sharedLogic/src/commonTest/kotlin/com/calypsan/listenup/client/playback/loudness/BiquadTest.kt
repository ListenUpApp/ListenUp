package com.calypsan.listenup.client.playback.loudness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class BiquadTest : FunSpec({
    test("K-weighting stage 1 (high-shelf) matches BS.1770-4 48kHz reference") {
        val b = Biquad.kWeightingStage1(48_000)
        b.b0 shouldBe (1.53512485958697 plusOrMinus 1e-6)
        b.b1 shouldBe (-2.69169618940638 plusOrMinus 1e-6)
        b.b2 shouldBe (1.19839281085285 plusOrMinus 1e-6)
        b.a1 shouldBe (-1.69065929318241 plusOrMinus 1e-6)
        b.a2 shouldBe (0.73248077421585 plusOrMinus 1e-6)
    }

    test("K-weighting stage 2 (high-pass) matches BS.1770-4 48kHz reference") {
        val b = Biquad.kWeightingStage2(48_000)
        b.b0 shouldBe (1.0 plusOrMinus 1e-6)
        b.b1 shouldBe (-2.0 plusOrMinus 1e-6)
        b.b2 shouldBe (1.0 plusOrMinus 1e-6)
        b.a1 shouldBe (-1.99004745483398 plusOrMinus 1e-5)
        b.a2 shouldBe (0.99007225036621 plusOrMinus 1e-5)
    }

    test("processing a DC-blocked highpass removes constant offset over time") {
        val hp = Biquad.kWeightingStage2(48_000)
        var last = 0.0
        repeat(48_000) { last = hp.process(1.0) }
        last shouldBe (0.0 plusOrMinus 0.01)
    }
})
