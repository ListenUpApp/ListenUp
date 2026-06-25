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
 * Algorithm:
 * 1. Build a standard Huffman tree via a sorted-list min-heap.
 * 2. Read depths from the parent-pointer tree.
 * 3. If any depth exceeds [maxBits], clip all overlong codes to [maxBits] and then iteratively
 *    lengthen the shortest codes until the Kraft inequality `Σ 2^−len ≤ 1` is restored.
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

    // If max depth is already within the limit, we are done.
    if ((depths.maxOrNull() ?: 0) <= maxBits) return depths

    // Clip all depths that exceed maxBits.
    for (i in depths.indices) {
        if (depths[i] > maxBits) depths[i] = maxBits
    }

    // Restore the Kraft inequality using integer arithmetic scaled by 2^maxBits.
    // capacity  = 2^maxBits (total available code space)
    // usage(d)  = 2^(maxBits − d)   (space consumed by a single code of length d)
    // Increasing depth d → d+1 frees 2^(maxBits − d − 1) units of capacity.
    val capacity = 1L shl maxBits
    var used = depths.sumOf { d -> if (d > 0) 1L shl (maxBits - d) else 0L }

    // Lengthen the shortest codes first (they free the most capacity per step).
    while (used > capacity) {
        var bestIdx = -1
        var bestDepth = Int.MAX_VALUE
        for (i in depths.indices) {
            if (depths[i] in 1 until maxBits && depths[i] < bestDepth) {
                bestDepth = depths[i]
                bestIdx = i
            }
        }
        if (bestIdx == -1) break // Cannot increase further — tree is already at maxBits everywhere.
        used -= 1L shl (maxBits - depths[bestIdx])
        depths[bestIdx]++
        used += 1L shl (maxBits - depths[bestIdx])
    }

    return depths
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
