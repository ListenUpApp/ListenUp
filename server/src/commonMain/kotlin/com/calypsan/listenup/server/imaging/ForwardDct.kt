package com.calypsan.listenup.server.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The forward 8x8 DCT, and the quantisation that follows it.
 *
 * **Separable, not naive.** The 2-D transform factors into eight row transforms and eight column
 * transforms — 1,024 multiplies per block instead of 4,096 — which is the difference between a
 * derivative pipeline and a slow one, at no cost in accuracy.
 *
 * This is the textbook definition rather than one of the fast integer approximations (AAN, Loeffler).
 * Those are worth real money in a video encoder running thousands of frames a second; here the whole
 * job is a few thousand blocks per cover, once, on a background worker, and an exact transform is
 * one fewer thing that can be subtly wrong.
 */
internal fun forwardDct(block: DoubleArray): DoubleArray {
    val rows = DoubleArray(BLOCK_COEFFICIENTS)
    for (y in 0 until DCT_SIZE) {
        for (u in 0 until DCT_SIZE) {
            var sum = 0.0
            for (x in 0 until DCT_SIZE) sum += block[y * DCT_SIZE + x] * COSINES[x * DCT_SIZE + u]
            rows[y * DCT_SIZE + u] = sum * NORMALISERS[u]
        }
    }

    val out = DoubleArray(BLOCK_COEFFICIENTS)
    for (u in 0 until DCT_SIZE) {
        for (v in 0 until DCT_SIZE) {
            var sum = 0.0
            for (y in 0 until DCT_SIZE) sum += rows[y * DCT_SIZE + u] * COSINES[y * DCT_SIZE + v]
            out[v * DCT_SIZE + u] = sum * NORMALISERS[v]
        }
    }
    return out
}

/** Divides each coefficient by its quantisation value and rounds — where the loss in "lossy" is. */
internal fun quantise(
    coefficients: DoubleArray,
    quant: IntArray,
): IntArray = IntArray(BLOCK_COEFFICIENTS) { (coefficients[it] / quant[it]).roundToInt() }

/** `cos((2x + 1) * u * PI / 16)`, the only transcendental work, done once. */
private val COSINES =
    DoubleArray(BLOCK_COEFFICIENTS) { index ->
        val x = index / DCT_SIZE
        val u = index % DCT_SIZE
        cos((2 * x + 1) * u * PI / (2 * DCT_SIZE))
    }

/** The 1-D normaliser: `C(u)/2`, where `C(0)` is `1/sqrt(2)` and every other `C(u)` is 1. */
private val NORMALISERS =
    DoubleArray(DCT_SIZE) { u -> if (u == 0) 1.0 / sqrt(2.0) / 2.0 else 0.5 }
