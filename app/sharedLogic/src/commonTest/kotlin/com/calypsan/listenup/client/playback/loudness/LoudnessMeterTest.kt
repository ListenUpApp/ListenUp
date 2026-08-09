package com.calypsan.listenup.client.playback.loudness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus as floatPlusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin

private fun sineMono(
    sampleRate: Int,
    seconds: Double,
    freq: Double,
    amplitude: Double,
): FloatArray {
    val n = (sampleRate * seconds).toInt()
    return FloatArray(n) { i -> (amplitude * sin(2.0 * PI * freq * i / sampleRate)).toFloat() }
}

class LoudnessMeterTest :
    FunSpec({
        val fs = 48_000

        test("silence produces no gated measurement (null)") {
            val m = LoudnessMeter(sampleRate = fs, channelCount = 1)
            m.addFrames(FloatArray(fs * 2), fs * 2)
            m.integratedLufs().shouldBeNull()
        }

        test("steady 1kHz tone gives a stable reading around the calibration point") {
            val m = LoudnessMeter(sampleRate = fs, channelCount = 1)
            val tone = sineMono(fs, 3.0, 1000.0, 0.1)
            m.addFrames(tone, tone.size)
            val lufs = m.integratedLufs()!!
            lufs shouldBe (-23.0 plusOrMinus 1.5)
        }

        test("a +6 dB louder signal measures +6 LU higher") {
            val quiet =
                LoudnessMeter(fs, 1).apply {
                    val s = sineMono(fs, 3.0, 1000.0, 0.1)
                    addFrames(s, s.size)
                }
            val loud =
                LoudnessMeter(fs, 1).apply {
                    val s = sineMono(fs, 3.0, 1000.0, 0.2)
                    addFrames(s, s.size)
                }
            (loud.integratedLufs()!! - quiet.integratedLufs()!!) shouldBe (6.02 plusOrMinus 0.1)
        }

        test("gainToTarget: normalizationGain = TARGET - measured") {
            val m = LoudnessMeter(fs, 1)
            val s = sineMono(fs, 3.0, 1000.0, 0.1)
            m.addFrames(s, s.size)
            val measured = m.integratedLufs()!!
            m.normalizationGainDb()!! shouldBe ((LoudnessMeter.TARGET_LUFS - measured).toFloat() floatPlusOrMinus 0.01f)
        }
    })
