@file:Suppress("MagicNumber", "NestedBlockDepth", "UnnecessaryParentheses")

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
        if (blurHash.length < 6) return null

        val sizeFlag = decode83(blurHash, 0, 1)
        val numY = (sizeFlag / 9) + 1
        val numX = (sizeFlag % 9) + 1

        val expectedLength = 4 + 2 * numX * numY
        if (blurHash.length != expectedLength) return null

        val quantisedMaximumValue = decode83(blurHash, 1, 2)
        val maximumValue = (quantisedMaximumValue + 1) / 166f

        val colors =
            Array(numX * numY) { i ->
                if (i == 0) {
                    decodeDC(decode83(blurHash, 2, 6))
                } else {
                    decodeAC(decode83(blurHash, 4 + i * 2, 4 + i * 2 + 2), maximumValue)
                }
            }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f

                for (j in 0 until numY) {
                    for (i in 0 until numX) {
                        val basis =
                            cos(PI * i * x / width).toFloat() *
                                cos(PI * j * y / height).toFloat()
                        val color = colors[i + j * numX]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }

                val intR = linearToSRGB(r)
                val intG = linearToSRGB(g)
                val intB = linearToSRGB(b)
                pixels[y * width + x] = (0xFF shl 24) or (intR shl 16) or (intG shl 8) or intB
            }
        }

        return pixels
    }

    private fun decode83(
        str: String,
        from: Int,
        to: Int,
    ): Int {
        var value = 0
        for (i in from until to) {
            val index = CHARS.indexOf(str[i])
            if (index == -1) return 0
            value = value * 83 + index
        }
        return value
    }

    private fun decodeDC(value: Int): FloatArray {
        val r = value shr 16
        val g = (value shr 8) and 255
        val b = value and 255
        return floatArrayOf(sRGBToLinear(r), sRGBToLinear(g), sRGBToLinear(b))
    }

    private fun decodeAC(
        value: Int,
        maximumValue: Float,
    ): FloatArray {
        val quantR = value / (19 * 19)
        val quantG = (value / 19) % 19
        val quantB = value % 19
        return floatArrayOf(
            signPow((quantR - 9f) / 9f, 2f) * maximumValue,
            signPow((quantG - 9f) / 9f, 2f) * maximumValue,
            signPow((quantB - 9f) / 9f, 2f) * maximumValue,
        )
    }

    private fun signPow(
        value: Float,
        exp: Float,
    ): Float = sign(value) * abs(value).pow(exp)

    private fun sRGBToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSRGB(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb * 255 + 0.5f).toInt().coerceIn(0, 255)
    }
}
