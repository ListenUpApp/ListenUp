package com.calypsan.listenup.client.playback.loudness

import kotlin.math.pow

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
    /** Manual boost floor: "off". */
    const val MIN_BOOST_DB: Float = 0f

    /** Manual boost ceiling. Clipping at the top is accepted by design. */
    const val MAX_BOOST_DB: Float = 12f

    /** Convert a decibel gain to a linear amplitude multiplier. */
    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    /**
     * Combine the measured/tagged normalization and the user's boost into one dB value.
     * Measured normalization wins over the file-tag value; both fall back to 0.
     */
    fun effectiveGainDb(
        measuredGainDb: Float?,
        bookNormalizationGainDb: Float?,
        userBoostDb: Float,
    ): Float = (measuredGainDb ?: bookNormalizationGainDb ?: 0f) + userBoostDb

    /** Scale one sample by a precomputed linear gain and hard-clamp to the format ceiling. */
    fun applySample(
        sample: Float,
        linearGain: Float,
    ): Float = (sample * linearGain).coerceIn(-1f, 1f)
}
