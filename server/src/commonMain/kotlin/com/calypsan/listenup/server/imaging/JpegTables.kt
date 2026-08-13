package com.calypsan.listenup.server.imaging

/*
 * The tables the JPEG specification's Annex K supplies, and the quality knob that scales them.
 *
 * These are not our tables and must not be tuned: a decoder reads quantisation values out of the
 * stream, but the *Huffman* tables here are the ones every baseline encoder ships, so their code
 * lengths are what the standard's example statistics say is efficient for photographs.
 *
 * 🔑 They were extracted from a PIL-written fixture, not transcribed. The 162-symbol AC lists are
 * exactly where a typo hides — a wrong symbol yields a file that decodes to plausible garbage. The
 * quantisation tables were then checked the other way: scaling them to quality 92 reproduces PIL's
 * DQT segments byte for byte.
 *
 * ⚠️ They are carried as text rather than `intArrayOf(...)` because the formatter explodes an array
 * literal to ONE ENTRY PER LINE — 693 lines, and worse, it destroys the 8x8 shape that is the whole
 * point of a quantisation matrix. A raw string is the one literal the formatter will not touch.
 * `JpegTablesTest` pins the structure that moving to text put at risk.
 */

/** Parses a whitespace-separated table. The layout of the text is the layout of the table. */
private fun numbers(text: String): IntArray =
    text
        .trim()
        .split(" ", "\n", "\r", "\t")
        .filter { it.isNotBlank() }
        .map { it.toInt() }
        .toIntArray()

/** Zig-zag order → natural block position. Shared: encoders walk it forwards, decoders backwards. */
internal val ZIGZAG =
    numbers(
        """
         0  1  8 16  9  2  3 10 17 24 32 25 18 11  4  5
        12 19 26 33 40 48 41 34 27 20 13  6  7 14 21 28
        35 42 49 56 57 50 43 36 29 22 15 23 30 37 44 51
        58 59 52 45 38 31 39 46 53 60 61 54 47 55 62 63
        """.trimIndent(),
    )

/** Annex K.1 luminance quantisation, natural order. Quality 50 is this table unscaled. */
internal val BASE_QUANT_LUMA =
    numbers(
        """
        16  11  10  16  24  40  51  61
        12  12  14  19  26  58  60  55
        14  13  16  24  40  57  69  56
        14  17  22  29  51  87  80  62
        18  22  37  56  68 109 103  77
        24  35  55  64  81 104 113  92
        49  64  78  87 103 121 120 101
        72  92  95  98 112 100 103  99
        """.trimIndent(),
    )

/** Annex K.2 chrominance quantisation, natural order — flat and coarse past the low frequencies. */
internal val BASE_QUANT_CHROMA =
    numbers(
        """
        17 18 24 47 99 99 99 99
        18 21 26 66 99 99 99 99
        24 26 56 99 99 99 99 99
        47 66 99 99 99 99 99 99
        99 99 99 99 99 99 99 99
        99 99 99 99 99 99 99 99
        99 99 99 99 99 99 99 99
        99 99 99 99 99 99 99 99
        """.trimIndent(),
    )

/**
 * Scales a base table for [quality] (1–100), the libjpeg formula every encoder uses.
 *
 * Below 50 the divisor grows hyperbolically and above it falls linearly, which is why 50 is the
 * hinge and why quality is not a percentage of anything. Values clamp to 1–255 because a
 * quantisation value of zero would divide by zero and 8-bit tables cannot carry more than 255.
 */
internal fun scaleQuantTable(
    base: IntArray,
    quality: Int,
): IntArray {
    val clamped = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
    val scale = if (clamped < QUALITY_HINGE) MAGIC_5000 / clamped else FULL_SCALE - clamped * 2
    return IntArray(BLOCK_COEFFICIENTS) {
        ((base[it] * scale + ROUNDING_HALF) / PERCENT).coerceIn(MIN_QUANT, MAX_QUANT)
    }
}

internal const val MIN_QUALITY = 1
internal const val MAX_QUALITY = 100
private const val QUALITY_HINGE = 50
private const val MAGIC_5000 = 5000
private const val FULL_SCALE = 200
private const val ROUNDING_HALF = 50
private const val PERCENT = 100
private const val MIN_QUANT = 1
private const val MAX_QUANT = 255

internal val STANDARD_DC_LUMA_COUNTS =
    numbers(
        """
        0   1   5   1   1   1   1   1   1   0   0   0   0   0   0   0
        """.trimIndent(),
    )

internal val STANDARD_DC_LUMA_VALUES =
    numbers(
        """
        0   1   2   3   4   5   6   7   8   9  10  11
        """.trimIndent(),
    )

internal val STANDARD_AC_LUMA_COUNTS =
    numbers(
        """
        0   2   1   3   3   2   4   3   5   5   4   4   0   0   1 125
        """.trimIndent(),
    )

internal val STANDARD_AC_LUMA_VALUES =
    numbers(
        """
          1   2   3   0   4  17   5  18  33  49  65   6  19  81  97   7
         34 113  20  50 129 145 161   8  35  66 177 193  21  82 209 240
         36  51  98 114 130   9  10  22  23  24  25  26  37  38  39  40
         41  42  52  53  54  55  56  57  58  67  68  69  70  71  72  73
         74  83  84  85  86  87  88  89  90  99 100 101 102 103 104 105
        106 115 116 117 118 119 120 121 122 131 132 133 134 135 136 137
        138 146 147 148 149 150 151 152 153 154 162 163 164 165 166 167
        168 169 170 178 179 180 181 182 183 184 185 186 194 195 196 197
        198 199 200 201 202 210 211 212 213 214 215 216 217 218 225 226
        227 228 229 230 231 232 233 234 241 242 243 244 245 246 247 248
        249 250
        """.trimIndent(),
    )

internal val STANDARD_DC_CHROMA_COUNTS =
    numbers(
        """
        0   3   1   1   1   1   1   1   1   1   1   0   0   0   0   0
        """.trimIndent(),
    )

internal val STANDARD_DC_CHROMA_VALUES =
    numbers(
        """
        0   1   2   3   4   5   6   7   8   9  10  11
        """.trimIndent(),
    )

internal val STANDARD_AC_CHROMA_COUNTS =
    numbers(
        """
        0   2   1   2   4   4   3   4   7   5   4   4   0   1   2 119
        """.trimIndent(),
    )

internal val STANDARD_AC_CHROMA_VALUES =
    numbers(
        """
          0   1   2   3  17   4   5  33  49   6  18  65  81   7  97 113
         19  34  50 129   8  20  66 145 161 177 193   9  35  51  82 240
         21  98 114 209  10  22  36  52 225  37 241  23  24  25  26  38
         39  40  41  42  53  54  55  56  57  58  67  68  69  70  71  72
         73  74  83  84  85  86  87  88  89  90  99 100 101 102 103 104
        105 106 115 116 117 118 119 120 121 122 130 131 132 133 134 135
        136 137 138 146 147 148 149 150 151 152 153 154 162 163 164 165
        166 167 168 169 170 178 179 180 181 182 183 184 185 186 194 195
        196 197 198 199 200 201 202 210 211 212 213 214 215 216 217 218
        226 227 228 229 230 231 232 233 234 242 243 244 245 246 247 248
        249 250
        """.trimIndent(),
    )
