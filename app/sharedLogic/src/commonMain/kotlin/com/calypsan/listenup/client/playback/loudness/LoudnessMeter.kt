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

    private val blockPowers = ArrayList<Double>()

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
                blockPowers.add(ringSum / blockFrames)
            }
        }
    }

    /** Integrated loudness in LUFS, or null if no block clears the absolute gate. */
    fun integratedLufs(): Double? {
        if (blockPowers.isEmpty()) return null
        val absKept = blockPowers.filter { powerToLufs(it) >= ABSOLUTE_GATE_LUFS }
        if (absKept.isEmpty()) return null
        val relThreshold = powerToLufs(absKept.average()) - RELATIVE_GATE_LU
        val relKept = absKept.filter { powerToLufs(it) >= relThreshold }
        if (relKept.isEmpty()) return null
        return powerToLufs(relKept.average())
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
        blockPowers.clear()
    }

    private fun powerToLufs(power: Double): Double =
        if (power <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10.0 * log10(power)

    companion object {
        /** Standard spoken-word normalization target. */
        const val TARGET_LUFS: Double = -18.0
        private const val ABSOLUTE_GATE_LUFS = -70.0
        private const val RELATIVE_GATE_LU = 10.0
    }
}
