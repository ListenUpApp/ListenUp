package com.calypsan.listenup.client.design.timeline

/**
 * Shades the minimap: one normalised weight per bucket across the whole book.
 *
 * A 311-chapter book drawn as 311 marks across a minimap is one mark every four pixels — a grey
 * smear that tells you nothing. Bucketing the same starts and shading by how many fall in each
 * turns them into structure a reader can actually see: where the short chapters cluster, where the
 * long ones sit, where the book changes pace.
 *
 * Weights are normalised against the busiest bucket rather than reported as counts, because an
 * absolute count cannot be drawn honestly — six per bucket is crowded in one book and empty in
 * another, and only the shape *within this book* carries meaning. The busiest bucket is therefore
 * always `1f` and everything else is relative to it.
 *
 * Starts outside `0..bookDurationMs` are ignored. They should not occur, but a mid-flight drift
 * apply is capable of producing one, and letting it through would inflate the maximum and wash out
 * the whole minimap — a confusing, entirely visual failure with no error attached to it.
 *
 * @param chapterStartsMs absolute chapter start times; order does not matter.
 * @param bookDurationMs total length of the book. Zero yields all-zero weights rather than `NaN`.
 * @param bucketCount how many buckets to draw. Returns exactly this many.
 * @return one weight in `0f..1f` per bucket, in time order.
 */
fun chapterDensity(
    chapterStartsMs: List<Long>,
    bookDurationMs: Long,
    bucketCount: Int,
): List<Float> {
    if (bucketCount <= 0) return emptyList()
    val counts = IntArray(bucketCount)
    if (bookDurationMs > 0L) {
        chapterStartsMs.forEach { start ->
            if (start in 0L..bookDurationMs) {
                // Integer arithmetic, not doubles: `start / duration * buckets` in floating point
                // lands on 0.9999… for exactly-divisible inputs, so evenly-spaced chapters come
                // out jittered by one bucket and a flat book draws with a false ripple in it.
                // Long math is exact here — the product cannot overflow for any real book length.
                //
                // The coercion catches a chapter starting on the final millisecond, which belongs
                // in the last bucket rather than one past the end.
                val bucket = (start * bucketCount / bookDurationMs).toInt().coerceAtMost(bucketCount - 1)
                counts[bucket]++
            }
        }
    }
    val busiest = counts.max()
    if (busiest == 0) return List(bucketCount) { 0f }
    return counts.map { it.toFloat() / busiest }
}
