package com.calypsan.listenup.client.playback.loudness

import kotlin.math.log10

/**
 * EBU R128 / ITU-R BS.1770-4 integrated-loudness meter over streamed float PCM.
 *
 * Feed interleaved frames as they decode; call [integratedLufs] / [normalizationGainDb] at any
 * time for the loudness of everything seen so far. Progressive by design: for audiobooks
 * (listened start-to-finish) the estimate refines as coverage grows.
 *
 * Stateful, single-threaded (drive from the audio thread). Pure Kotlin so both platform gain
 * stages feed the same instance.
 */
class LoudnessMeter(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private val stage1 = Array(channelCount) { Biquad.kWeightingStage1(sampleRate) }
    private val stage2 = Array(channelCount) { Biquad.kWeightingStage2(sampleRate) }

    private val blockFrames = (sampleRate * 0.4).toInt()
    private val hopFrames = (sampleRate * 0.1).toInt()

    private val squares = DoubleArray(blockFrames)
    private var writeIndex = 0
    private var filledInHop = 0
    private var totalFramesInRing = 0
    private var ringSum = 0.0

    // Fixed-size gating histogram (libebur128 shape): bins of 0.1 LU spanning
    // [ABSOLUTE_GATE_LUFS, HISTOGRAM_CEILING_LUFS). Each bin keeps the block count and the
    // sum of mean-square powers, so gated averages stay exact up to boundary quantization.
    private val binCounts = LongArray(HISTOGRAM_BINS)
    private val binPowerSums = DoubleArray(HISTOGRAM_BINS)

    private fun binIndex(lufs: Double): Int? {
        if (lufs < ABSOLUTE_GATE_LUFS) return null // absolute gate: below -70 LUFS is never stored
        val idx = ((lufs - ABSOLUTE_GATE_LUFS) / BIN_WIDTH_LU).toInt()
        return idx.coerceAtMost(HISTOGRAM_BINS - 1)
    }

    /** Feed [frameCount] interleaved frames of [channelCount] channels each. */
    fun addFrames(
        interleaved: FloatArray,
        frameCount: Int,
    ) {
        var idx = 0
        for (f in 0 until frameCount) {
            var channelSumSq = 0.0
            for (c in 0 until channelCount) {
                val k = stage2[c].process(stage1[c].process(interleaved[idx++].toDouble()))
                channelSumSq += k * k
            }
            ringSum -= squares[writeIndex]
            squares[writeIndex] = channelSumSq
            ringSum += channelSumSq
            writeIndex = (writeIndex + 1) % blockFrames
            if (totalFramesInRing < blockFrames) totalFramesInRing++
            filledInHop++
            if (totalFramesInRing >= blockFrames && filledInHop >= hopFrames) {
                filledInHop = 0
                val power = ringSum / blockFrames
                binIndex(powerToLufs(power))?.let { bin ->
                    binCounts[bin]++
                    binPowerSums[bin] += power
                }
            }
        }
    }

    /** Integrated loudness in LUFS, or null if no block clears the absolute gate. */
    fun integratedLufs(): Double? {
        var count = 0L
        var powerSum = 0.0
        for (bin in 0 until HISTOGRAM_BINS) {
            count += binCounts[bin]
            powerSum += binPowerSums[bin]
        }
        if (count == 0L) return null
        val relThreshold = powerToLufs(powerSum / count) - RELATIVE_GATE_LU
        var keptCount = 0L
        var keptPowerSum = 0.0
        val firstKeptBin = binIndex(relThreshold) ?: 0
        for (bin in firstKeptBin until HISTOGRAM_BINS) {
            keptCount += binCounts[bin]
            keptPowerSum += binPowerSums[bin]
        }
        if (keptCount == 0L) return null
        return powerToLufs(keptPowerSum / keptCount)
    }

    /** `TARGET_LUFS - integrated`, or null if unmeasurable. Positive = boost, negative = attenuate. */
    fun normalizationGainDb(): Float? = integratedLufs()?.let { (TARGET_LUFS - it).toFloat() }

    /** Clear all accumulated blocks and filter state, e.g. on seek or track change. */
    fun reset() {
        stage1.forEach { it.reset() }
        stage2.forEach { it.reset() }
        squares.fill(0.0)
        writeIndex = 0
        filledInHop = 0
        totalFramesInRing = 0
        ringSum = 0.0
        binCounts.fill(0)
        binPowerSums.fill(0.0)
    }

    private fun powerToLufs(power: Double): Double =
        if (power <= 0.0) Double.NEGATIVE_INFINITY else LUFS_OFFSET + DECIBEL_SCALE * log10(power)

    companion object {
        /** Standard spoken-word normalization target. */
        const val TARGET_LUFS: Double = -18.0
        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_LU = 10.0

        // BS.1770-4 loudness equation, L = -0.691 + 10 * log10(mean power): the K-weighting
        // calibration offset and the power-to-decibel scale factor.
        private const val LUFS_OFFSET = -0.691
        private const val DECIBEL_SCALE = 10.0

        private const val BIN_WIDTH_LU = 0.1
        private const val HISTOGRAM_CEILING_LUFS = 10.0
        private const val HISTOGRAM_BINS =
            ((HISTOGRAM_CEILING_LUFS - ABSOLUTE_GATE_LUFS) / BIN_WIDTH_LU).toInt() // 800
    }
}
