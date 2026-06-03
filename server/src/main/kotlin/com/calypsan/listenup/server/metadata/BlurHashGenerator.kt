
package com.calypsan.listenup.server.metadata

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Pure-Kotlin BlurHash encoder. Generates a compact perceptual hash of an
 * image, used as a placeholder while the full image loads. Output is a short
 * ASCII string (typically 20–30 chars).
 *
 * Encoder is server-side only — clients receive the string and decode via
 * the existing [com.calypsan.listenup.client.design.util.BlurHashCore] decoder
 * (or platform equivalents) when rendering.
 *
 * Algorithm reference: https://github.com/woltapp/blurhash/blob/master/Algorithm.md
 *
 * Input pixels are ARGB-packed (alpha in top byte, then R, G, B). Alpha is
 * ignored — BlurHash encodes the RGB channels only. Components are the number
 * of DCT basis functions per axis; 4×3 is the spec's recommended default and
 * produces hashes around 28 chars.
 */
// The DCT/sRGB-gamma float arithmetic and the base-83 / base-19 AC packing
// constants are canonical values fixed by the BlurHash algorithm
// (https://github.com/woltapp/blurhash/blob/master/Algorithm.md).
@Suppress("MagicNumber")
object BlurHashGenerator {
    private const val MAX_COMPONENTS = 9
    private const val MIN_COMPONENTS = 1

    /** The base-83 radix used for every digit of the BlurHash string. */
    private const val BASE83 = 83

    // The base-83 alphabet, identical to the spec and to BlurHashCore's CHARS.
    private const val BASE83_CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    /**
     * Encodes the given image into a BlurHash string.
     *
     * @param pixels ARGB-packed pixels, row-major. Must have size == width * height.
     * @param width image width (>= 1)
     * @param height image height (>= 1)
     * @param componentsX number of x-axis DCT components (1..9). Default 4.
     * @param componentsY number of y-axis DCT components (1..9). Default 3.
     * @return the BlurHash string
     * @throws IllegalArgumentException if component counts are out of range or
     *   if pixels.size != width * height.
     */
    fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        componentsX: Int = 4,
        componentsY: Int = 3,
    ): String {
        require(componentsX in MIN_COMPONENTS..MAX_COMPONENTS) {
            "componentsX must be in $MIN_COMPONENTS..$MAX_COMPONENTS, was $componentsX"
        }
        require(componentsY in MIN_COMPONENTS..MAX_COMPONENTS) {
            "componentsY must be in $MIN_COMPONENTS..$MAX_COMPONENTS, was $componentsY"
        }
        require(width >= 1 && height >= 1) { "width and height must be >= 1" }
        require(pixels.size == width * height) {
            "pixels.size (${pixels.size}) must equal width * height (${width * height})"
        }

        // Step 1: compute all (componentsX * componentsY) DCT factors
        val factors =
            Array(componentsX * componentsY) { idx ->
                val i = idx % componentsX
                val j = idx / componentsX
                computeDctFactor(pixels, width, height, i, j)
            }

        val dc = factors[0]
        val ac = factors.drop(1)

        // Step 2: determine the AC maximum value
        val maxAcComponent = ac.flatMap { it.toList() }.maxOrNull()?.let { abs(it) } ?: 0f

        // Step 3: build the hash string
        val result = StringBuilder()

        // Size flag: encodes (numX, numY) as a single base-83 digit
        val sizeFlag = (componentsY - 1) * 9 + (componentsX - 1)
        result.append(encodeBase83(sizeFlag, 1))

        // Quantised AC maximum: one base-83 digit
        val quantisedMaximumValue =
            if (ac.isEmpty()) {
                0
            } else {
                max(0, (maxAcComponent * 166 - 0.5f).roundToInt().coerceIn(0, 82))
            }
        result.append(encodeBase83(quantisedMaximumValue, 1))

        // DC component: 4 base-83 digits
        result.append(encodeBase83(encodeDc(dc), 4))

        // AC components: 2 base-83 digits each
        val maximumValue = if (quantisedMaximumValue > 0) (quantisedMaximumValue + 1f) / 166f else 1f
        for (factor in ac) {
            result.append(encodeBase83(encodeAc(factor, maximumValue), 2))
        }

        return result.toString()
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Computes the (i, j) DCT factor over [pixels]. The normalisation
     * coefficient is 1 for the DC component (i=0, j=0) and 2 for all AC
     * components, per the BlurHash spec.
     */
    private fun computeDctFactor(
        pixels: IntArray,
        width: Int,
        height: Int,
        i: Int,
        j: Int,
    ): FloatArray {
        val normalisation = if (i == 0 && j == 0) 1f else 2f
        val factor = FloatArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val basis =
                    normalisation *
                        cos(PI * i * x / width).toFloat() *
                        cos(PI * j * y / height).toFloat()
                val pixel = pixels[y * width + x]
                factor[0] += basis * sRgbToLinear((pixel shr 16) and 0xFF)
                factor[1] += basis * sRgbToLinear((pixel shr 8) and 0xFF)
                factor[2] += basis * sRgbToLinear(pixel and 0xFF)
            }
        }
        val scale = 1f / (width * height)
        factor[0] *= scale
        factor[1] *= scale
        factor[2] *= scale
        return factor
    }

    /** Converts a single sRGB channel value (0–255) to linear light. */
    private fun sRgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Packs the DC factor (linear RGB triple) into a 24-bit integer. */
    private fun encodeDc(factor: FloatArray): Int {
        val r = linearToSRgb(factor[0])
        val g = linearToSRgb(factor[1])
        val b = linearToSRgb(factor[2])
        return (r shl 16) or (g shl 8) or b
    }

    /** Encodes a single AC factor into a compact integer in [0, 82*83+82]. */
    private fun encodeAc(
        factor: FloatArray,
        maximumValue: Float,
    ): Int {
        val r = (signPow(factor[0] / maximumValue, 0.5f) * 9f + 9.5f).toInt().coerceIn(0, 18)
        val g = (signPow(factor[1] / maximumValue, 0.5f) * 9f + 9.5f).toInt().coerceIn(0, 18)
        val b = (signPow(factor[2] / maximumValue, 0.5f) * 9f + 9.5f).toInt().coerceIn(0, 18)
        return r * 19 * 19 + g * 19 + b
    }

    /** Converts a linear light value to sRGB channel (0–255). */
    private fun linearToSRgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb * 255 + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Encodes [value] as exactly [length] base-83 digits (big-endian, most
     * significant digit first), appending them into a string.
     */
    private fun encodeBase83(
        value: Int,
        length: Int,
    ): String {
        val result = CharArray(length)
        for (i in 1..length) {
            val digit = value / pow83(length - i) % BASE83
            result[i - 1] = BASE83_CHARS[digit]
        }
        return String(result)
    }

    /** Integer powers of 83 — only small values needed. */
    private fun pow83(n: Int): Int {
        var v = 1
        repeat(n) { v *= BASE83 }
        return v
    }

    /** sign(value) * |value|^exp */
    private fun signPow(
        value: Float,
        exp: Float,
    ): Float = sign(value) * abs(value).pow(exp)
}
