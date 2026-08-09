package com.calypsan.listenup.client.playback.loudness

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.tan

/**
 * A normalized second-order IIR section (Direct Form I). The two K-weighting factories reproduce
 * the ITU-R BS.1770-4 pre-filter via its analog-prototype bilinear transform (tangent prewarping
 * of the corner frequency, not a simple cos/sin substitution) — the form used by the reference
 * implementation, which is what reproduces the published 48 kHz coefficients exactly and
 * generalizes cleanly to any sample rate.
 *
 * Stateful and NOT thread-safe: one filter per channel, driven from the audio thread.
 */
class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /** Filter one sample and advance the internal state by one step. */
    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    /** Clear the delay line, e.g. on seek or track change. */
    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    companion object {
        private const val SHELF_F0 = 1681.974450955533
        private const val SHELF_GAIN_DB = 3.999843853973347
        private const val SHELF_Q = 0.7071752369554196
        private const val SHELF_VB_EXPONENT = 0.4996667741545416
        private const val HP_F0 = 38.13547087602444
        private const val HP_Q = 0.5003270373238773

        // Fixed RLB high-pass numerator — a double zero at DC: b0 = 1, b1 = -2, b2 = 1.
        private const val HP_B0 = 1.0
        private const val HP_B1 = -2.0
        private const val HP_B2 = 1.0

        /** Stage 1: the BS.1770-4 high-frequency shelving pre-filter. */
        fun kWeightingStage1(sampleRate: Int): Biquad {
            val k = tan(PI * SHELF_F0 / sampleRate)
            val vh = 10.0.pow(SHELF_GAIN_DB / 20.0)
            val vb = vh.pow(SHELF_VB_EXPONENT)
            val denom = 1.0 + k / SHELF_Q + k * k
            val b0 = (vh + vb * k / SHELF_Q + k * k) / denom
            val b1 = 2.0 * (k * k - vh) / denom
            val b2 = (vh - vb * k / SHELF_Q + k * k) / denom
            val a1 = 2.0 * (k * k - 1.0) / denom
            val a2 = (1.0 - k / SHELF_Q + k * k) / denom
            return Biquad(b0, b1, b2, a1, a2)
        }

        /** Stage 2: the BS.1770-4 RLB high-pass filter. */
        fun kWeightingStage2(sampleRate: Int): Biquad {
            val k = tan(PI * HP_F0 / sampleRate)
            val denom = 1.0 + k / HP_Q + k * k
            val a1 = 2.0 * (k * k - 1.0) / denom
            val a2 = (1.0 - k / HP_Q + k * k) / denom
            return Biquad(HP_B0, HP_B1, HP_B2, a1, a2)
        }
    }
}
