package com.calypsan.listenup.server.compression

/**
 * LZ77 match finder (RFC 1951 §4): turns raw bytes into a stream of literal/back-reference tokens.
 *
 * Each output token is packed into an [Int]:
 * - a **literal** is its byte value `0..255` (the [MATCH_FLAG] bit is clear);
 * - a **match** is `MATCH_FLAG or (length shl LENGTH_SHIFT) or distance`, where `length` is `3..258`
 *   and `distance` is `1..32768`.
 *
 * Matches are found with a hash-chain over a rolling 3-byte hash: [head] maps a hash bucket to the
 * most recent position with that hash, and [prev] chains earlier positions sharing the bucket. The
 * search at each position walks the chain — bounded by a [level]-derived chain limit — for the
 * longest run that fits DEFLATE's `3..258` length / `1..32768` distance limits. The strategy is
 * greedy: the longest match at the current position is taken immediately. Every scanned position
 * (including those covered by an emitted match) is inserted into the chain so later positions can
 * reference it.
 */
internal fun lz77(
    data: ByteArray,
    level: Int,
): IntArray {
    val size = data.size
    if (size == 0) return IntArray(0)

    // A token consumes at least one input byte, so there can never be more tokens than input bytes.
    val tokens = IntArray(size)
    var tokenCount = 0

    val head = IntArray(HASH_SIZE) { -1 }
    val prev = IntArray(size)
    val maxChain = maxChainFor(level)

    var pos = 0
    while (pos < size) {
        var matchLen = 0
        var matchDist = 0

        if (pos + MIN_MATCH <= size) {
            val bucket = hash3(data, pos)
            val maxLen = minOf(MAX_MATCH, size - pos)
            var candidate = head[bucket]
            var chain = maxChain
            while (candidate >= 0 && pos - candidate <= WINDOW_SIZE && chain-- > 0) {
                // Skip candidates that cannot beat the current best (zlib's tail-byte shortcut).
                if (matchLen == 0 || data[candidate + matchLen] == data[pos + matchLen]) {
                    val len = matchLength(data, candidate, pos, maxLen)
                    if (len > matchLen) {
                        matchLen = len
                        matchDist = pos - candidate
                        if (len >= maxLen) break
                    }
                }
                candidate = prev[candidate]
            }
            prev[pos] = head[bucket]
            head[bucket] = pos
        }

        if (matchLen >= MIN_MATCH) {
            tokens[tokenCount++] = MATCH_FLAG or (matchLen shl LENGTH_SHIFT) or matchDist
            // Insert every position the match covers so later matches can reference them.
            val stop = pos + matchLen
            var next = pos + 1
            while (next < stop) {
                if (next + MIN_MATCH <= size) {
                    val bucket = hash3(data, next)
                    prev[next] = head[bucket]
                    head[bucket] = next
                }
                next++
            }
            pos = stop
        } else {
            tokens[tokenCount++] = data[pos].toInt() and 0xFF
            pos++
        }
    }

    return tokens.copyOf(tokenCount)
}

/** Length of the common run of [data] starting at [candidate] and [pos], capped at [maxLen]. */
private fun matchLength(
    data: ByteArray,
    candidate: Int,
    pos: Int,
    maxLen: Int,
): Int {
    var len = 0
    while (len < maxLen && data[candidate + len] == data[pos + len]) len++
    return len
}

/** Maps the three bytes at [index] to a [HASH_SIZE] bucket via a multiplicative (Fibonacci) hash. */
private fun hash3(
    data: ByteArray,
    index: Int,
): Int {
    val key =
        ((data[index].toInt() and 0xFF) shl 16) or
            ((data[index + 1].toInt() and 0xFF) shl 8) or
            (data[index + 2].toInt() and 0xFF)
    return (key * HASH_MULTIPLIER) ushr (32 - HASH_BITS)
}

/** Chain-walk budget per position: short for fast levels, long for thorough ones. */
private fun maxChainFor(level: Int): Int =
    when (level) {
        1 -> 8
        2 -> 16
        3 -> 32
        4 -> 64
        5 -> 128
        6 -> 256
        7 -> 512
        8 -> 1024
        else -> 4096
    }

internal const val MATCH_FLAG = 1 shl 30
internal const val LENGTH_SHIFT = 16
internal const val DISTANCE_MASK = 0xFFFF

private const val MIN_MATCH = 3
private const val MAX_MATCH = 258
private const val WINDOW_SIZE = 32768
private const val HASH_BITS = 15
private const val HASH_SIZE = 1 shl HASH_BITS
private const val HASH_MULTIPLIER = -1640531527 // 0x9E3779B9, Knuth's Fibonacci-hashing constant.
