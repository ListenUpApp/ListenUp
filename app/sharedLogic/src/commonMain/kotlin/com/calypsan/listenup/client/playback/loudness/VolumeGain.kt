package com.calypsan.listenup.client.playback.loudness

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The single source of truth for how the three gain inputs combine into one linear multiplier,
 * and how a PCM sample is scaled by it. Pure and platform-free so both platform gain stages
 * call the same math.
 *
 * `effectiveGainDb = normalizationGainDb + userBoostDb`, where normalization prefers the
 * client-measured value over a server-read file tag (a real measurement is trusted over a
 * possibly-stale tag), falling back to 0 dB when neither exists.
 */
object VolumeGain {
    /** dB-to-amplitude decade divisor: 20 dB per factor-of-10 change in amplitude. */
    private const val DB_PER_DECADE = 20f

    /** Convert a decibel gain to a linear amplitude multiplier. */
    fun dbToLinear(db: Float): Float = 10f.pow(db / DB_PER_DECADE)

    /**
     * Combine the measured/tagged normalization and the user's boost into one dB value.
     * Measured normalization wins over the file-tag value; both fall back to 0.
     */
    fun effectiveGainDb(
        measuredGainDb: Float?,
        bookNormalizationGainDb: Float?,
        userBoostDb: Float,
    ): Float = (measuredGainDb ?: bookNormalizationGainDb ?: 0f) + userBoostDb

    /**
     * Where the soft knee begins, in linear amplitude (-3 dBFS).
     *
     * Below this, [applySample] is exactly a multiply — audio that was never going to clip is
     * bit-identical to what it was before saturation existed. Only the top 3 dB is shaped.
     */
    const val KNEE_LINEAR: Float = 0.708f

    /**
     * The asymptote the saturator approaches, just under full scale (-0.009 dB).
     *
     * Not 1.0, for two reasons. `tanh` returns exactly `1.0f` once its argument passes roughly 9,
     * so a 1.0 asymptote would let hard-driven samples land exactly on full scale — and "never
     * reaches the rail" is a guarantee worth being able to assert. It also leaves the 16-bit
     * quantizer a hair of room, so a negative peak cannot round onto the two's-complement floor.
     * The level difference is four orders of magnitude below audibility.
     */
    const val CEILING_LINEAR: Float = 0.999f

    /**
     * Scale one sample by a precomputed linear gain, saturating smoothly into the ceiling.
     *
     * The old behaviour was `coerceIn(-1f, 1f)` — a brick wall. Squaring off a waveform
     * manufactures high-order harmonics, which is heard as a buzz, and it is heard most on a phone
     * speaker: a speaker that reproduces nothing below a few hundred Hz gives you the harmonics
     * without the fundamental that would otherwise mask them.
     *
     * Above [KNEE_LINEAR] the excess is passed through `tanh`, which
     *  - matches the linear slope exactly at the knee (`tanh'(0) = 1`), so there is no corner and
     *    no discontinuity to hear as a click,
     *  - is monotonic, so louder input is always louder output — the boost keeps boosting, and
     *  - asymptotes to [CEILING_LINEAR] without ever reaching it, so no clamp is needed at all.
     *
     * It is still distortion when driven hard. The difference is that it is low-order and rolls
     * off, which reads as compression rather than as a fault.
     */
    fun applySample(
        sample: Float,
        linearGain: Float,
    ): Float {
        val scaled = sample * linearGain
        val magnitude = abs(scaled)
        if (magnitude <= KNEE_LINEAR) return scaled
        val headroom = CEILING_LINEAR - KNEE_LINEAR
        val saturated = KNEE_LINEAR + headroom * tanh((magnitude - KNEE_LINEAR) / headroom)
        return if (scaled < 0f) -saturated else saturated
    }
}
