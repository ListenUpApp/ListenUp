package com.calypsan.listenup.client.playback.loudness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThan as floatShouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Narration-shaped mono audio with a controllable peak-to-loudness ratio.
 *
 * A quiet sustained bed (the voice) plus sparse short transients (plosives, page turns, the
 * consonants that set a recording's peak). Real narration's peaks come from those transients, not
 * from the sustained level, which is exactly why peak and loudness can drift so far apart — and
 * why a normalizer that reads only loudness can ask for more gain than the file has room for.
 *
 * @param bed amplitude of the sustained voice.
 * @param transient amplitude of the sparse peaks; this sets the file's peak.
 */
private fun narration(
    sampleRate: Int,
    seconds: Double,
    bed: Double,
    transient: Double,
): FloatArray {
    val n = (sampleRate * seconds).toInt()
    val transientLen = (sampleRate * 0.003).toInt() // 3 ms, a plosive
    val transientEvery = (sampleRate * 0.9).toInt() // a little under one per second
    return FloatArray(n) { i ->
        val t = i.toDouble() / sampleRate
        // Syllabic envelope on the bed, so the meter sees speech-like gating rather than a tone.
        val syllable = 0.5 * (1.0 - cos(2.0 * PI * 4.0 * t))
        val voice = bed * syllable * sin(2.0 * PI * 220.0 * i / sampleRate)
        val intoTransient = i % transientEvery
        val spike =
            if (intoTransient < transientLen) {
                val shape = sin(PI * intoTransient / transientLen)
                transient * shape * sin(2.0 * PI * 3000.0 * i / sampleRate)
            } else {
                0.0
            }
        (voice + spike).coerceIn(-1.0, 1.0).toFloat()
    }
}

private fun FloatArray.peakLinear(): Float = maxOf { abs(it) }

private fun linearToDbfs(linear: Float): Double = 20.0 * log10(linear.toDouble())

private fun round1(value: Double): Double = (value * 10).toInt() / 10.0

/**
 * When the volume-boost chain hard-clips, and why.
 *
 * The chain is `effectiveGainDb = (measured ?: tagged ?: 0) + userBoost`, applied by
 * [VolumeGain.applySample], which hard-clamps to ±1.0. [LoudnessMeter.normalizationGainDb] returns
 * `TARGET_LUFS - integrated` with no reference to the material's peak, and nothing between the two
 * knows a peak exists.
 *
 * Solving for when the clamp actually engages:
 * ```
 *   clips  ⟺  peak_dBFS + gain > 0
 *          ⟺  peak_dBFS + (TARGET_LUFS − integrated) + boost > 0
 *          ⟺  PLR > −TARGET_LUFS − boost           where PLR = peak_dBFS − integrated
 *          ⟺  PLR > 18 − boost
 * ```
 * So normalization alone clips only material with a peak-to-loudness ratio above 18 dB. The user's
 * boost lowers that bar one dB for one: at +6 dB it clips anything above 12 dB PLR, and at the
 * +12 dB ceiling anything above 6 dB — which is all speech.
 *
 * [com.calypsan.listenup.domain.VolumeBoostLimits] documents "clipping at the top is accepted by
 * design". That was a defensible call for a boost applied to a raw file. Normalization now sits
 * underneath it and raises every book to −18 LUFS first, so the boost is being applied to a signal
 * that has already had its headroom spent.
 *
 * Hard clipping is not quiet distortion — it manufactures high-order harmonics, which is what gets
 * reported as a buzz. It is far more audible on a phone speaker than on headphones: a phone speaker
 * reproduces almost nothing below a few hundred Hz, so the fundamental that would otherwise mask
 * those harmonics is simply absent. The defect is route-independent; only its audibility is not.
 */
class NormalizationClippingTest :
    FunSpec({
        val fs = 48_000

        test("DIAGNOSTIC: the clipping threshold, measured against the real meter") {
            val cases =
                listOf(
                    "compressed commercial" to narration(fs, 12.0, bed = 0.30, transient = 0.35),
                    "ordinary narration" to narration(fs, 12.0, bed = 0.10, transient = 0.50),
                    "wide-dynamics / self-produced" to narration(fs, 12.0, bed = 0.02, transient = 0.70),
                )
            for ((label, signal) in cases) {
                val meter = LoudnessMeter(sampleRate = fs, channelCount = 1)
                meter.addFrames(signal, signal.size)
                val integrated = meter.integratedLufs() ?: error("unmeasurable")
                val gainDb = meter.normalizationGainDb() ?: error("unmeasurable")
                val peakDbfs = linearToDbfs(signal.peakLinear())
                val plr = peakDbfs - integrated
                val row =
                    listOf(0f, 3f, 6f, 12f).joinToString("  ") { boost ->
                        val effective = VolumeGain.effectiveGainDb(gainDb, null, boost)
                        val linear = VolumeGain.dbToLinear(effective)
                        val clipped = signal.count { abs(VolumeGain.applySample(it, linear)) >= 1f }
                        "+${boost.toInt()}dB:$clipped"
                    }
                println(
                    """
                    |
                    |--- $label
                    |  integrated : ${round1(integrated)} LUFS   peak: ${round1(peakDbfs)} dBFS   PLR: ${round1(plr)} dB
                    |  normalization asks for : ${round1(gainDb.toDouble())} dB
                    |  clipped samples by user boost : $row   (of ${signal.size})
                    """.trimMargin(),
                )
            }
            true shouldBe true
        }

        test("normalization never asks for more gain than the material has headroom for") {
            // The defect: two files at the same loudness with peaks 10 dB apart were offered the
            // same gain, because the gain read loudness alone. Now the peaky one is capped.
            val roomToSpare = narration(fs, 12.0, bed = 0.10, transient = 0.12)
            val noRoom = narration(fs, 12.0, bed = 0.10, transient = 0.70)

            fun measure(signal: FloatArray): Pair<Float, Double> {
                val meter = LoudnessMeter(sampleRate = fs, channelCount = 1)
                meter.addFrames(signal, signal.size)
                val gain = meter.normalizationGainDb() ?: error("unmeasurable")
                return gain to linearToDbfs(signal.peakLinear())
            }

            val (tightGain, tightPeak) = measure(noRoom)
            val (looseGain, _) = measure(roomToSpare)

            // The peaky file is held back...
            tightGain floatShouldBeLessThan looseGain
            // ...to exactly the headroom it has, less the inter-sample safety margin.
            (tightPeak + tightGain).toDouble() shouldBeLessThan -(LoudnessMeter.PEAK_SAFETY_DB - 0.15).toDouble()
        }

        test("no book is clipped by its own normalization gain, at any user boost") {
            // The end-to-end guarantee. Every profile, every preset the boost UI offers: nothing
            // reaches full scale, so nothing squares off.
            val profiles =
                listOf(
                    narration(fs, 12.0, bed = 0.30, transient = 0.35),
                    narration(fs, 12.0, bed = 0.10, transient = 0.50),
                    narration(fs, 12.0, bed = 0.02, transient = 0.70),
                )
            for (signal in profiles) {
                val meter = LoudnessMeter(sampleRate = fs, channelCount = 1)
                meter.addFrames(signal, signal.size)
                for (boost in listOf(0f, 3f, 6f, 9f, 12f)) {
                    val effective =
                        VolumeGain.effectiveGainDb(meter.normalizationGainDb(), null, boost)
                    val linear = VolumeGain.dbToLinear(effective)
                    val atFullScale = signal.count { abs(VolumeGain.applySample(it, linear)) >= 1f }
                    atFullScale shouldBe 0
                }
            }
        }

        test("a book already at or above target is still attenuated, uncapped") {
            // The cap must only ever restrain a boost. An attenuation is safe by construction, and
            // clamping it would leave loud books loud — the opposite of normalizing.
            val loud = narration(fs, 12.0, bed = 0.55, transient = 0.60)
            val meter = LoudnessMeter(sampleRate = fs, channelCount = 1)
            meter.addFrames(loud, loud.size)

            val integrated = meter.integratedLufs() ?: error("unmeasurable")
            val gain = meter.normalizationGainDb() ?: error("unmeasurable")

            integrated shouldBeGreaterThan LoudnessMeter.TARGET_LUFS
            gain.toDouble() shouldBeLessThan 0.0
            // Exactly what loudness asked for, with no headroom cap applied.
            gain.toDouble() shouldBe (LoudnessMeter.TARGET_LUFS - integrated plusOrMinus 0.05)
        }
    })
