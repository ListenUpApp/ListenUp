package com.calypsan.listenup.server.compression

/**
 * Builds canonical Huffman (code, length) per symbol from [lengths] (0 = unused). RFC 1951 §3.2.2.
 *
 * Returns `Pair(codes, lengths)` where `codes[i]` is the canonical integer code for symbol `i`.
 * The returned lengths array is the same object as the input.
 */
internal fun canonicalCodes(lengths: IntArray): Pair<IntArray, IntArray> {
    val maxLen = lengths.maxOrNull() ?: 0
    val codes = IntArray(lengths.size)
    if (maxLen == 0) return Pair(codes, lengths)

    // Step 1: count number of codes at each length.
    val blCount = IntArray(maxLen + 1)
    for (len in lengths) if (len > 0) blCount[len]++

    // Step 2: find the numerical value of the smallest code for each length (RFC §3.2.2).
    val nextCode = IntArray(maxLen + 1)
    var code = 0
    blCount[0] = 0
    for (bits in 1..maxLen) {
        code = (code + blCount[bits - 1]) shl 1
        nextCode[bits] = code
    }

    // Step 3: assign canonical codes in symbol order.
    for (n in lengths.indices) {
        val len = lengths[n]
        if (len != 0) {
            codes[n] = nextCode[len]
            nextCode[len]++
        }
    }

    return Pair(codes, lengths)
}

/**
 * Emits the low [len] bits of [code] MSB-first to [w].
 *
 * Canonical Huffman codes are transmitted MSB-first (the inverse of the LSB-first raw bit fields
 * handled by [BitWriter.writeBits]). Each bit is written individually in descending bit-position
 * order so the most significant bit arrives first at the decoder.
 */
internal fun writeHuffmanCode(
    w: BitWriter,
    code: Int,
    len: Int,
) {
    for (i in len - 1 downTo 0) w.writeBits((code ushr i) and 1, 1)
}

/**
 * Decodes symbols from a canonical Huffman table defined by [lengths] (0 = unused symbol).
 *
 * Reads one bit at a time MSB-first from a [BitReader], accumulating `code = (code shl 1) or bit`.
 * At each length, if `code − first[len]` falls within `[0, count[len])` the corresponding symbol
 * is returned. If no match is found within the maximum code length a [MalformedDeflateException]
 * is thrown.
 */
internal class HuffmanDecoder(
    lengths: IntArray,
) {
    private val maxLen: Int
    private val first: IntArray
    private val symbolsByLen: Array<IntArray>

    init {
        val maxL = lengths.maxOrNull() ?: 0
        maxLen = maxL

        // Build a per-length symbol list in ascending symbol order (matches canonical assignment).
        symbolsByLen =
            Array(maxL + 1) { len ->
                lengths.indices.filter { lengths[it] == len }.toIntArray()
            }

        // Compute the first (smallest) canonical code at each length — same as canonicalCodes step 2.
        val blCount = IntArray(maxL + 1)
        for (len in lengths) if (len > 0) blCount[len]++
        first = IntArray(maxL + 1)
        var c = 0
        blCount[0] = 0
        for (bits in 1..maxL) {
            c = (c + blCount[bits - 1]) shl 1
            first[bits] = c
        }
    }

    /** Reads one symbol MSB-first from [reader]. Throws [MalformedDeflateException] on a bad code. */
    fun decodeSymbol(reader: BitReader): Int {
        var code = 0
        for (len in 1..maxLen) {
            code = (code shl 1) or reader.readBits(1)
            val idx = code - first[len]
            if (idx >= 0 && idx < symbolsByLen[len].size) {
                return symbolsByLen[len][idx]
            }
        }
        throw MalformedDeflateException("invalid Huffman code")
    }
}

/**
 * Builds length-limited (≤ [maxBits]) canonical code lengths from symbol [freq].
 *
 * DEFLATE (RFC 1951) requires all code lengths ≤ 15; pass `maxBits = 15` for deflate streams.
 *
 * Returns a **complete** prefix code: `Σ 2^−len == 1` for any input with ≥ 2 used symbols. This is
 * stronger than mere validity (`Σ 2^−len ≤ 1`) and is required for interop — RFC-1951 inflaters
 * (zlib/gzip/browsers) reject an *incomplete* literal/length or distance table as `Z_DATA_ERROR`.
 *
 * Algorithm:
 * 1. Build a standard Huffman tree via a sorted-list min-heap and read each used symbol's natural
 *    depth from the parent-pointer tree.
 * 2. If the deepest natural code is within [maxBits], those depths already form a complete code — done.
 * 3. Otherwise redistribute using zlib's `gen_bitlen` overflow handling on the length histogram
 *    `blCount[]`: collapse every length > [maxBits] into the [maxBits] bucket, then repeatedly demote
 *    one shorter code and pull one over-deep code up as its sibling until the histogram is exactly
 *    complete again (each such step lowers `Σ 2^−len` by precisely `2^−maxBits`).
 * 4. Re-hand the resulting lengths to symbols shortest-first in descending-frequency order, so the
 *    most frequent symbols keep the shortest codes.
 *
 * Edge cases: 0 used symbols → all-zero lengths; exactly 1 used symbol → length 1.
 */
internal fun buildLengthLimitedLengths(
    freq: IntArray,
    maxBits: Int,
): IntArray {
    val n = freq.size
    val usedIndices = freq.indices.filter { freq[it] > 0 }

    if (usedIndices.isEmpty()) return IntArray(n)
    if (usedIndices.size == 1) return IntArray(n).also { it[usedIndices[0]] = 1 }

    // Allocate node storage: leaves 0..n-1, internal nodes n..n+(k-2) where k = usedIndices.size.
    val nodeCount = n + usedIndices.size
    val nodeWeight = IntArray(nodeCount) { if (it < n) freq[it] else 0 }
    val parent = IntArray(nodeCount) { -1 }
    var nextNode = n

    // Maintain a sorted active list (ascending weight, then ascending index for tie-breaking).
    val active =
        usedIndices
            .sortedWith(compareBy({ nodeWeight[it] }, { it }))
            .toMutableList()

    // Huffman merging: repeatedly combine the two lightest nodes.
    while (active.size > 1) {
        val a = active.removeAt(0)
        val b = active.removeAt(0)
        val p = nextNode++
        nodeWeight[p] = nodeWeight[a] + nodeWeight[b]
        parent[a] = p
        parent[b] = p
        // Sorted insertion: new node goes after all existing nodes with weight ≤ nodeWeight[p].
        var insertAt = active.size
        for (j in active.indices) {
            if (nodeWeight[active[j]] > nodeWeight[p]) {
                insertAt = j
                break
            }
        }
        active.add(insertAt, p)
    }

    // Compute code lengths by counting hops from each leaf to the root.
    val depths = IntArray(n)
    for (i in usedIndices) {
        var node = i
        var depth = 0
        while (parent[node] != -1) {
            node = parent[node]
            depth++
        }
        depths[i] = depth
    }

    // If the deepest natural code is already within the limit, the tree depths form a complete
    // code — return them directly (frequent symbols already hold the shortest codes).
    val naturalMax = depths.maxOrNull() ?: 0
    if (naturalMax <= maxBits) return depths

    // Histogram of natural code lengths (some buckets may sit above maxBits).
    val blCount = IntArray(naturalMax + 1)
    for (i in usedIndices) blCount[depths[i]]++

    // Collapse every over-deep code into the maxBits bucket. The histogram is now ≤ maxBits but
    // over-subscribed: Σ 2^−len > 1 (clipping deep codes shorter reclaims more code space than exists).
    for (len in naturalMax downTo maxBits + 1) {
        blCount[maxBits] += blCount[len]
        blCount[len] = 0
    }

    // zlib gen_bitlen redistribution. Working in code space scaled by 2^maxBits:
    //   used      = Σ blCount[len] · 2^(maxBits − len)   (an integer once all lengths ≤ maxBits)
    //   capacity  = 2^maxBits   (a complete code uses exactly this)
    // Each step demotes one code at `bits` to `bits+1` and lifts one code from the maxBits bucket up
    // as its sibling. The net effect on `used` is exactly −1, so we step until used == capacity — i.e.
    // until the histogram describes a *complete* code (Σ 2^−len == 1), never merely a valid one.
    val capacity = 1L shl maxBits
    var overflow = (1..maxBits).sumOf { blCount[it].toLong() shl (maxBits - it) } - capacity
    while (overflow > 0) {
        var bits = maxBits - 1
        while (bits > 0 && blCount[bits] == 0) bits--
        if (bits == 0) break // Unreachable while over capacity: codes must exist below maxBits.
        blCount[bits]--
        blCount[bits + 1] += 2
        blCount[maxBits]--
        overflow--
    }

    // Hand the histogram's lengths back to symbols shortest-first, most-frequent-first, so the
    // highest-frequency symbols keep the shortest codes (good compression — the inverse of clipping
    // the most frequent symbol). Ties break on symbol index for a deterministic assignment.
    val limited = IntArray(n)
    val byFrequencyDesc = usedIndices.sortedWith(compareByDescending<Int> { freq[it] }.thenBy { it })
    var cursor = 0
    for (len in 1..maxBits) {
        repeat(blCount[len]) { limited[byFrequencyDesc[cursor++]] = len }
    }

    return limited
}

/** Fixed literal/length code lengths (RFC §3.2.6): 0..143 = 8, 144..255 = 9, 256..279 = 7, 280..287 = 8. */
internal val FIXED_LITLEN_LENGTHS: IntArray =
    IntArray(288) {
        when {
            it <= 143 -> 8
            it <= 255 -> 9
            it <= 279 -> 7
            else -> 8
        }
    }

/** Fixed distance code lengths (RFC §3.2.6): all 5 bits. */
internal val FIXED_DIST_LENGTHS: IntArray = IntArray(30) { 5 }
