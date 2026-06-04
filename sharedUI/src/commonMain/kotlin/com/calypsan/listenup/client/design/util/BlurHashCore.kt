package com.calypsan.listenup.client.design.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign

/**
 * Pure-Kotlin BlurHash decoder.
 *
 * Implements the BlurHash algorithm (https://blurha.sh) with zero
 * platform dependencies. Returns raw ARGB pixel array that each
 * platform converts to its native image type.
 */
object BlurHashCore {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    /** Base of the BlurHash string encoding alphabet (see [CHARS]). */
    private const val BASE83 = 83

    /** Minimum valid BlurHash length: size flag, max-AC quantiser, and one DC component. */
    private const val MIN_HASH_LENGTH = 6

    /** Maximum number of components per axis encoded in the single size-flag digit. */
    private const val MAX_COMPONENTS = 9

    /** Number of quantisation levels per AC color channel. */
    private const val AC_LEVELS = 19

    /** Midpoint of the [AC_LEVELS] quantisation range, used to centre AC values on zero. */
    private const val AC_MIDPOINT = 9f

    /** Divisor that dequantises the encoded maximum-AC value into a float scale. */
    private const val MAX_AC_DIVISOR = 166f

    /** 8-bit color channel mask / range bound. */
    private const val CHANNEL_MAX = 255

    /** Bit position of the alpha channel in a packed ARGB int. */
    private const val ALPHA_SHIFT = 24

    /** Bit position of the red channel in a packed ARGB int. */
    private const val RED_SHIFT = 16

    /** Bit position of the green channel in a packed ARGB int. */
    private const val GREEN_SHIFT = 8

    /**
     * Decode a BlurHash string to an IntArray of ARGB pixels.
     *
     * @param blurHash The BlurHash string
     * @param width Output width in pixels
     * @param height Output height in pixels
     * @return IntArray of ARGB pixels (size = width * height), or null if invalid
     */
    fun decode(
        blurHash: String,
        width: Int,
        height: Int,
    ): IntArray? {
        if (blurHash.length < MIN_HASH_LENGTH) return null

        val sizeFlag = decode83(blurHash, 0, 1)
        val numY = (sizeFlag / MAX_COMPONENTS) + 1
        val numX = (sizeFlag % MAX_COMPONENTS) + 1

        val expectedLength = 4 + 2 * numX * numY
        if (blurHash.length != expectedLength) return null

        val quantisedMaximumValue = decode83(blurHash, 1, 2)
        val maximumValue = (quantisedMaximumValue + 1) / MAX_AC_DIVISOR

        val colors =
            Array(numX * numY) { i ->
                if (i == 0) {
                    decodeDC(decode83(blurHash, 2, MIN_HASH_LENGTH))
                } else {
                    decodeAC(decode83(blurHash, 4 + i * 2, 4 + i * 2 + 2), maximumValue)
                }
            }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = pixelAt(x, y, width, height, numX, numY, colors)
            }
        }

        return pixels
    }

    @Suppress("LongParameterList") // Pure DSP inner loop; bundling args into a holder would obscure the math.
    private fun pixelAt(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        numX: Int,
        numY: Int,
        colors: Array<FloatArray>,
    ): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        for (j in 0 until numY) {
            for (i in 0 until numX) {
                val basis = cos(PI * i * x / width).toFloat() * cos(PI * j * y / height).toFloat()
                val color = colors[i + j * numX]
                r += color[0] * basis
                g += color[1] * basis
                b += color[2] * basis
            }
        }
        return argb(linearToSRGB(r), linearToSRGB(g), linearToSRGB(b))
    }

    /** Pack three 8-bit channels into an opaque ARGB int. */
    private fun argb(
        red: Int,
        green: Int,
        blue: Int,
    ): Int = (CHANNEL_MAX shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue

    private fun decode83(
        str: String,
        from: Int,
        to: Int,
    ): Int {
        var value = 0
        for (i in from until to) {
            val index = CHARS.indexOf(str[i])
            if (index == -1) return 0
            value = value * BASE83 + index
        }
        return value
    }

    private fun decodeDC(value: Int): FloatArray {
        val r = value shr RED_SHIFT
        val g = (value shr GREEN_SHIFT) and CHANNEL_MAX
        val b = value and CHANNEL_MAX
        return floatArrayOf(sRGBToLinear(r), sRGBToLinear(g), sRGBToLinear(b))
    }

    private fun decodeAC(
        value: Int,
        maximumValue: Float,
    ): FloatArray {
        val quantR = value / (AC_LEVELS * AC_LEVELS)
        val quantG = value / AC_LEVELS % AC_LEVELS
        val quantB = value % AC_LEVELS
        return floatArrayOf(
            normaliseAC(quantR) * maximumValue,
            normaliseAC(quantG) * maximumValue,
            normaliseAC(quantB) * maximumValue,
        )
    }

    /** Centre a quantised AC level on zero and apply BlurHash's signed-square companding. */
    @Suppress("MagicNumber") // 2f is BlurHash's fixed signed-square companding exponent.
    private fun normaliseAC(quantised: Int): Float = signPow((quantised - AC_MIDPOINT) / AC_MIDPOINT, exp = 2f)

    private fun signPow(
        value: Float,
        exp: Float,
    ): Float = sign(value) * abs(value).pow(exp)

    /** sRGB transfer function constants per IEC 61966-2-1; naming each adds noise, not clarity. */
    @Suppress("MagicNumber")
    private fun sRGBToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Inverse sRGB transfer function per IEC 61966-2-1; naming each constant adds noise, not clarity. */
    @Suppress("MagicNumber")
    private fun linearToSRGB(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb * 255 + 0.5f).toInt().coerceIn(0, 255)
    }
}
