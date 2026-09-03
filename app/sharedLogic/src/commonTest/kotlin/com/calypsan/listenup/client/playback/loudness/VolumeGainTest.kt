package com.calypsan.listenup.client.playback.loudness

import com.calypsan.listenup.domain.VolumeBoostLimits
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

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

        test("applySample: below the knee it is exactly a multiply") {
            // The guarantee that matters for fidelity: audio that was never going to clip is not
            // touched by the saturator at all.
            val g = VolumeGain.dbToLinear(12f)
            VolumeGain.applySample(0.1f, g) shouldBe (0.398f plusOrMinus 0.01f)
            VolumeGain.applySample(0.1f, g) shouldBe (0.1f * g)
            VolumeGain.applySample(-0.1f, g) shouldBe (-0.1f * g)
        }

        test("applySample: above the knee it saturates smoothly instead of squaring off") {
            val g = VolumeGain.dbToLinear(12f)
            // 0.5 * 3.98 = 1.99, nearly 6 dB past full scale. The old clamp returned exactly 1.0 —
            // a flat top, which is what manufactures the buzz.
            val hot = VolumeGain.applySample(0.5f, g)
            hot shouldBeLessThan VolumeGain.CEILING_LINEAR
            hot shouldBeGreaterThan 0.99f
            VolumeGain.applySample(-0.5f, g) shouldBe -hot
        }

        test("applySample: never exceeds full scale, however hard it is driven") {
            val absurd = VolumeGain.dbToLinear(60f)
            for (sample in listOf(0.01f, 0.2f, 0.708f, 0.9f, 1f)) {
                abs(VolumeGain.applySample(sample, absurd)) shouldBeLessThan 1f
                abs(VolumeGain.applySample(-sample, absurd)) shouldBeLessThan 1f
                abs(VolumeGain.applySample(sample, absurd)) shouldBeLessThan VolumeGain.CEILING_LINEAR + 1e-6f
            }
        }

        test("applySample: continuous at the knee, so the transition cannot be heard as a click") {
            val justUnder = VolumeGain.applySample(VolumeGain.KNEE_LINEAR - 0.0001f, 1f)
            val justOver = VolumeGain.applySample(VolumeGain.KNEE_LINEAR + 0.0001f, 1f)
            abs(justOver - justUnder) shouldBeLessThan 0.001f
        }

        test("VOLUME BOOST STILL WORKS: every preset is louder than the one below it") {
            // The regression this guards against is the naive fix for clipping — capping total gain
            // at the material's headroom — which would silently make the boost control do nothing.
            // Boost must remain monotonic in output level across its whole advertised range.
            val signal =
                FloatArray(4_800) { i ->
                    (0.35 * sin(2.0 * PI * 220.0 * i / 48_000)).toFloat()
                }

            fun rmsAtBoost(db: Float): Double {
                val linear = VolumeGain.dbToLinear(db)
                var sumSquares = 0.0
                for (sample in signal) {
                    val out = VolumeGain.applySample(sample, linear)
                    sumSquares += out.toDouble() * out.toDouble()
                }
                return sqrt(sumSquares / signal.size)
            }

            val levels = listOf(0f, 3f, 6f, 9f, 12f).map(::rmsAtBoost)
            levels.zipWithNext().forAll { (quieter, louder) ->
                louder shouldBeGreaterThan quieter
            }
            // And it is a real increase, not a rounding artefact: +12 dB is audibly louder than off.
            (levels.last() / levels.first()) shouldBeGreaterThan 2.0
        }

        test("the curve never decreases, at any drive") {
            // The property that actually matters and that the curve really has: raising a sample
            // never lowers its output. Strict increase is impossible near the ceiling — see the
            // plateau note on VolumeGain.applySample.
            for (db in listOf(0f, 6f, 12f, 24f)) {
                val gain = VolumeGain.dbToLinear(db)
                var previous = -1f
                for (step in 0..1000) {
                    val out = VolumeGain.applySample(step / 1000f, gain)
                    out shouldBeGreaterThanOrEqual previous
                    previous = out
                }
            }
        }

        test("saturation flattens far less of the waveform than the old hard clamp did") {
            // The user-facing claim, measured rather than asserted. Both approaches eventually
            // plateau; what changed is how much of the signal reaches that plateau. Hard clipping
            // flattened everything past 1/gain, which at +12 dB is three quarters of the range.
            val gain = VolumeGain.dbToLinear(12f)
            val steps = 10_000

            fun flattenedFraction(transform: (Float) -> Float): Double {
                val outputs = (0..steps).map { transform(it / steps.toFloat()) }
                val ceiling = outputs.max()
                return outputs.count { it >= ceiling }.toDouble() / outputs.size
            }

            val hardClamped = flattenedFraction { (it * gain).coerceIn(-1f, 1f) }
            val saturated = flattenedFraction { VolumeGain.applySample(it, gain) }

            // The old behaviour flattened most of the range outright.
            hardClamped shouldBeGreaterThan 0.7
            // The new one leaves the great majority of it intact...
            saturated shouldBeLessThan 0.35
            // ...and is a large improvement, not a rounding difference.
            (hardClamped / saturated) shouldBeGreaterThan 2.0
        }

        test("boost range constants") {
            VolumeBoostLimits.MIN_DB shouldBe 0f
            VolumeBoostLimits.MAX_DB shouldBe 12f
        }
    })
