package com.calypsan.listenup.server.compression

/**
 * Builds and writes DEFLATE dynamic-Huffman blocks (BTYPE=10, RFC 1951 §3.2.7) from an LZ77 token
 * stream.
 *
 * The flow is split in two so the caller can choose the cheaper of a dynamic block and a stored
 * block before committing any bits: [planDynamicBlock] tallies the symbol frequencies, builds the
 * three length-limited Huffman code tables (literal/length, distance, code-length), run-length
 * encodes the code-length table, and reports the exact bit cost; [emitDynamicBlock] then replays
 * that plan onto a [BitWriter].
 */
internal class DynamicBlockPlan(
    val litlenLengths: IntArray,
    val distLengths: IntArray,
    val codeLengthLengths: IntArray,
    val rleSymbols: IntArray,
    val rleExtras: IntArray,
    val numLitLen: Int,
    val numDist: Int,
    val numCodeLength: Int,
    val totalBits: Long,
)

/** Tallies [tokens], builds the dynamic-block Huffman tables, and computes the block's exact bit cost. */
internal fun planDynamicBlock(tokens: IntArray): DynamicBlockPlan {
    val litlenFreq = IntArray(LITLEN_SYMBOLS)
    val distFreq = IntArray(DIST_SYMBOLS)
    for (token in tokens) {
        if (token and MATCH_FLAG == 0) {
            litlenFreq[token]++
        } else {
            val length = (token ushr LENGTH_SHIFT) and LENGTH_FIELD_MASK
            val distance = token and DISTANCE_MASK
            litlenFreq[LENGTH_BASE_SYMBOL + LENGTH_CODE_INDEX[length]]++
            distFreq[DIST_CODE_INDEX[distance]]++
        }
    }
    litlenFreq[END_OF_BLOCK]++ // end-of-block appears exactly once

    val litlenLengths = buildLengthLimitedLengths(litlenFreq, MAX_CODE_LENGTH)
    // DEFLATE requires at least one distance code even when no back-references were emitted; a single
    // length-1 code is the canonical "no distances used" encoding (zlib accepts an incomplete distance
    // table only in this max-code-length-1 case).
    val distLengths =
        if (distFreq.any { it > 0 }) {
            buildLengthLimitedLengths(distFreq, MAX_CODE_LENGTH)
        } else {
            IntArray(DIST_SYMBOLS).also { it[0] = 1 }
        }

    val numLitLen = trimmedCount(litlenLengths, MIN_LITLEN_CODES)
    val numDist = trimmedCount(distLengths, MIN_DIST_CODES)

    // Code lengths form one sequence (litlen then dist); repeats may cross the boundary (RFC §3.2.7).
    val combined = IntArray(numLitLen + numDist)
    litlenLengths.copyInto(combined, 0, 0, numLitLen)
    distLengths.copyInto(combined, numLitLen, 0, numDist)
    val (rleSymbols, rleExtras) = runLengthEncode(combined)

    val codeLengthFreq = IntArray(CODE_LENGTH_SYMBOLS)
    for (symbol in rleSymbols) codeLengthFreq[symbol]++
    val codeLengthLengths = buildLengthLimitedLengths(codeLengthFreq, MAX_CL_CODE_LENGTH)
    val numCodeLength = trimmedCodeLengthCount(codeLengthLengths)

    val totalBits =
        dynamicBitCost(tokens, litlenLengths, distLengths, codeLengthLengths, rleSymbols, numCodeLength)

    return DynamicBlockPlan(
        litlenLengths = litlenLengths,
        distLengths = distLengths,
        codeLengthLengths = codeLengthLengths,
        rleSymbols = rleSymbols,
        rleExtras = rleExtras,
        numLitLen = numLitLen,
        numDist = numDist,
        numCodeLength = numCodeLength,
        totalBits = totalBits,
    )
}

/** Writes the dynamic block described by [plan] for [tokens], padding to a byte boundary and flushing. */
internal fun emitDynamicBlock(
    writer: BitWriter,
    tokens: IntArray,
    plan: DynamicBlockPlan,
    isFinal: Boolean,
) {
    writer.writeBits(if (isFinal) 1 else 0, 1) // BFINAL
    writer.writeBits(BTYPE_DYNAMIC, 2)
    writer.writeBits(plan.numLitLen - MIN_LITLEN_CODES, 5) // HLIT
    writer.writeBits(plan.numDist - MIN_DIST_CODES, 5) // HDIST
    writer.writeBits(plan.numCodeLength - MIN_CL_CODES, 4) // HCLEN

    for (i in 0 until plan.numCodeLength) {
        writer.writeBits(plan.codeLengthLengths[CODE_LENGTH_ORDER[i]], 3)
    }

    val (codeLengthCodes, _) = canonicalCodes(plan.codeLengthLengths)
    for (i in plan.rleSymbols.indices) {
        val symbol = plan.rleSymbols[i]
        writeHuffmanCode(writer, codeLengthCodes[symbol], plan.codeLengthLengths[symbol])
        val extraBits = codeLengthExtraBits(symbol)
        if (extraBits > 0) writer.writeBits(plan.rleExtras[i], extraBits)
    }

    val (litCodes, _) = canonicalCodes(plan.litlenLengths)
    val (distCodes, _) = canonicalCodes(plan.distLengths)
    for (token in tokens) {
        if (token and MATCH_FLAG == 0) {
            writeHuffmanCode(writer, litCodes[token], plan.litlenLengths[token])
        } else {
            val length = (token ushr LENGTH_SHIFT) and LENGTH_FIELD_MASK
            val distance = token and DISTANCE_MASK
            val lengthIndex = LENGTH_CODE_INDEX[length]
            val lengthSymbol = LENGTH_BASE_SYMBOL + lengthIndex
            writeHuffmanCode(writer, litCodes[lengthSymbol], plan.litlenLengths[lengthSymbol])
            if (LENGTH_EXTRA[lengthIndex] > 0) {
                writer.writeBits(length - LENGTH_BASE[lengthIndex], LENGTH_EXTRA[lengthIndex])
            }
            val distIndex = DIST_CODE_INDEX[distance]
            writeHuffmanCode(writer, distCodes[distIndex], plan.distLengths[distIndex])
            if (DIST_EXTRA[distIndex] > 0) {
                writer.writeBits(distance - DIST_BASE[distIndex], DIST_EXTRA[distIndex])
            }
        }
    }
    writeHuffmanCode(writer, litCodes[END_OF_BLOCK], plan.litlenLengths[END_OF_BLOCK])

    // Only the final block pads to a byte boundary: non-final blocks must leave their trailing partial
    // bits for the next block to continue, since DEFLATE blocks do not begin on byte boundaries (RFC
    // §3.2.3). Padding between blocks would be decoded as a spurious stored-block header and corrupt
    // the stream. The whole-stream byte alignment happens once, on the last block emitted.
    if (isFinal) writer.alignToByte()
    writer.flush()
}

/**
 * Run-length-encodes a code-length sequence into code-length-code symbols (RFC §3.2.7): literal
 * lengths `0..15`, `16` (copy the previous length 3–6 times), `17` (a 3–10 zero run) and `18` (an
 * 11–138 zero run). Returns the symbol stream and the matching extra-bit values.
 */
private fun runLengthEncode(lengths: IntArray): Pair<IntArray, IntArray> {
    val symbols = ArrayList<Int>(lengths.size)
    val extras = ArrayList<Int>(lengths.size)
    var i = 0
    while (i < lengths.size) {
        val value = lengths[i]
        var runLength = 1
        while (i + runLength < lengths.size && lengths[i + runLength] == value) runLength++

        if (value == 0) {
            var remaining = runLength
            while (remaining >= LONG_ZERO_MIN) {
                val count = minOf(remaining, LONG_ZERO_MAX)
                symbols.add(REPEAT_ZERO_LONG)
                extras.add(count - LONG_ZERO_MIN)
                remaining -= count
            }
            while (remaining >= SHORT_ZERO_MIN) {
                val count = minOf(remaining, SHORT_ZERO_MAX)
                symbols.add(REPEAT_ZERO_SHORT)
                extras.add(count - SHORT_ZERO_MIN)
                remaining -= count
            }
            repeat(remaining) {
                symbols.add(0)
                extras.add(0)
            }
        } else {
            symbols.add(value)
            extras.add(0)
            var remaining = runLength - 1
            while (remaining >= COPY_PREV_MIN) {
                val count = minOf(remaining, COPY_PREV_MAX)
                symbols.add(REPEAT_PREVIOUS)
                extras.add(count - COPY_PREV_MIN)
                remaining -= count
            }
            repeat(remaining) {
                symbols.add(value)
                extras.add(0)
            }
        }
        i += runLength
    }
    return symbols.toIntArray() to extras.toIntArray()
}

/** Exact bit cost of a dynamic block: header, code-length table, RLE stream, tokens, and end-of-block. */
private fun dynamicBitCost(
    tokens: IntArray,
    litlenLengths: IntArray,
    distLengths: IntArray,
    codeLengthLengths: IntArray,
    rleSymbols: IntArray,
    numCodeLength: Int,
): Long {
    var bits = (1 + 2 + 5 + 5 + 4).toLong() // BFINAL + BTYPE + HLIT + HDIST + HCLEN
    bits += numCodeLength.toLong() * 3
    for (symbol in rleSymbols) bits += codeLengthLengths[symbol] + codeLengthExtraBits(symbol)
    for (token in tokens) {
        if (token and MATCH_FLAG == 0) {
            bits += litlenLengths[token]
        } else {
            val length = (token ushr LENGTH_SHIFT) and LENGTH_FIELD_MASK
            val distance = token and DISTANCE_MASK
            val lengthIndex = LENGTH_CODE_INDEX[length]
            val distIndex = DIST_CODE_INDEX[distance]
            bits += litlenLengths[LENGTH_BASE_SYMBOL + lengthIndex] + LENGTH_EXTRA[lengthIndex]
            bits += distLengths[distIndex] + DIST_EXTRA[distIndex]
        }
    }
    bits += litlenLengths[END_OF_BLOCK]
    return bits
}

/** Extra bits carried by a code-length-code symbol (`16`→2, `17`→3, `18`→7, otherwise 0). */
private fun codeLengthExtraBits(symbol: Int): Int =
    when (symbol) {
        REPEAT_PREVIOUS -> COPY_PREV_EXTRA
        REPEAT_ZERO_SHORT -> SHORT_ZERO_EXTRA
        REPEAT_ZERO_LONG -> LONG_ZERO_EXTRA
        else -> 0
    }

/** Highest index in [lengths] with a non-zero entry plus one, clamped up to [minimum]. */
private fun trimmedCount(
    lengths: IntArray,
    minimum: Int,
): Int {
    var count = lengths.size
    while (count > minimum && lengths[count - 1] == 0) count--
    return count
}

/** Like [trimmedCount] but walks the code-length codes in their transmission order (RFC §3.2.7). */
private fun trimmedCodeLengthCount(codeLengthLengths: IntArray): Int {
    var count = CODE_LENGTH_ORDER.size
    while (count > MIN_CL_CODES && codeLengthLengths[CODE_LENGTH_ORDER[count - 1]] == 0) count--
    return count
}

/** Maps a match length `3..258` to its length-code index `0..28` (RFC §3.2.5). */
internal val LENGTH_CODE_INDEX: IntArray = buildCodeIndex(LENGTH_BASE, MAX_MATCH_LENGTH + 1)

/** Maps a back-reference distance `1..32768` to its distance-code index `0..29` (RFC §3.2.5). */
internal val DIST_CODE_INDEX: IntArray = buildCodeIndex(DIST_BASE, MAX_DISTANCE + 1)

/** Expands a base-value table into a per-value → code-index lookup of [size] entries. */
private fun buildCodeIndex(
    base: IntArray,
    size: Int,
): IntArray {
    val table = IntArray(size)
    for (index in base.indices) {
        val from = base[index]
        val until = if (index + 1 < base.size) base[index + 1] else size
        for (value in from until until) table[value] = index
    }
    return table
}

private const val LITLEN_SYMBOLS = 286
private const val DIST_SYMBOLS = 30
private const val CODE_LENGTH_SYMBOLS = 19
private const val LENGTH_BASE_SYMBOL = 257
private const val END_OF_BLOCK = 256

private const val MAX_CODE_LENGTH = 15
private const val MAX_CL_CODE_LENGTH = 7
private const val MIN_LITLEN_CODES = 257
private const val MIN_DIST_CODES = 1
private const val MIN_CL_CODES = 4
private const val BTYPE_DYNAMIC = 2

private const val MAX_MATCH_LENGTH = 258
private const val MAX_DISTANCE = 32768
private const val LENGTH_FIELD_MASK = 0x1FF

private const val REPEAT_PREVIOUS = 16
private const val REPEAT_ZERO_SHORT = 17
private const val REPEAT_ZERO_LONG = 18

private const val COPY_PREV_MIN = 3
private const val COPY_PREV_MAX = 6
private const val COPY_PREV_EXTRA = 2
private const val SHORT_ZERO_MIN = 3
private const val SHORT_ZERO_MAX = 10
private const val SHORT_ZERO_EXTRA = 3
private const val LONG_ZERO_MIN = 11
private const val LONG_ZERO_MAX = 138
private const val LONG_ZERO_EXTRA = 7
