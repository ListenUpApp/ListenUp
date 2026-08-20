package com.calypsan.listenup.server.transcode

/**
 * Operator-tunable transcoding limits.
 *
 * ⚠️ **[cacheCapBytes] is a working set, not a high-water mark.** The design sized 10 GB on the
 * premise that only a format-mismatched slice of a library is ever transcoded; on a library that is
 * mostly xHE-AAC, played in a browser, that slice is most of it. The cap still holds — eviction is
 * what absorbs the difference — but expect it to churn rather than fill once. Revisit with real
 * usage; do not raise it speculatively.
 *
 * @param cacheCapBytes total bytes the segment cache may occupy; **0 disables transcoding**.
 * @param maxConcurrentSessions admission gate, not a queue — over-cap requests get a retryable busy.
 * @param bitrateKbps AAC-LC target. Channel count is preserved; sample rate is never resampled.
 * @param targetSegmentSeconds requested segment length; the real length is frame-aligned upward
 *   from this by the playlist builder.
 */
data class TranscodeSettings(
    val cacheCapBytes: Long = DEFAULT_CACHE_CAP_BYTES,
    val maxConcurrentSessions: Int = DEFAULT_MAX_CONCURRENT,
    val bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
    val targetSegmentSeconds: Int = DEFAULT_SEGMENT_SECONDS,
) {
    /** False when the operator has set the cap to zero. */
    val enabled: Boolean get() = cacheCapBytes > 0

    companion object {
        const val DEFAULT_CACHE_CAP_BYTES = 10L * 1024 * 1024 * 1024
        const val DEFAULT_MAX_CONCURRENT = 2
        const val DEFAULT_BITRATE_KBPS = 64
        const val DEFAULT_SEGMENT_SECONDS = 10
    }
}
