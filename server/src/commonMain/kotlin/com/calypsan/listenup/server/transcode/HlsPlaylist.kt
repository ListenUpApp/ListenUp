package com.calypsan.listenup.server.transcode

import kotlin.math.ceil

/**
 * Builds a complete VOD playlist for a file **before any of it is encoded**.
 *
 * Durations are already known from `embeddedmeta`, so the whole timeline can be written at prepare
 * time: a listener gets a full seek bar on a 40-hour book instantly, and any segment is addressable
 * before a byte is transcoded. Segment requests are what pull encoding forward.
 *
 * ⛔ **Segment length is frame-aligned, not the round number requested.** FFmpeg cuts AAC on
 * 1024-sample boundaries, so a 10s request at 44.1 kHz really produces 10.0078s. Declaring 10s
 * would drift by that much per segment — about four minutes across a 92-hour book — and every seek
 * would land wrong. Encode at the source sample rate; never resample, or this math describes a file
 * that was not produced.
 */
object HlsPlaylist {
    /** Frame size of every AAC object type this server encodes to (AAC-LC). */
    private const val SAMPLES_PER_FRAME = 1024

    /**
     * The dominant rate in a real library, used when a file has none recorded.
     *
     * Public because the encoder must be told the *same* rate this playlist's math assumed. If the
     * route guessed one fallback and the playlist another, every declared `#EXTINF` would describe
     * a file that was never written.
     */
    const val FALLBACK_SAMPLE_RATE = 44_100

    /** `#EXT-X-STREAM-INF` declares BANDWIDTH in bits per second. */
    private const val BITS_PER_KBIT = 1_000

    /** AAC-LC, the only profile this server encodes to. */
    private const val AAC_LC_CODEC_TAG = "mp4a.40.2"

    /** `#EXTINF` is written to microsecond precision — see [formatSeconds]. */
    private const val MICROS_PER_SECOND = 1_000_000L

    /** Digits after the decimal point in an `#EXTINF` duration. */
    private const val FRACTION_DIGITS = 6

    /** The segmentation of one file: how long each segment is, and how many there are. */
    data class Plan(
        val segmentSeconds: Double,
        val segmentDurations: List<Double>,
        /** The source rate the segment maths was computed from — never resampled on the way out. */
        val sampleRate: Int,
        /** Frames in every segment but the last. Exactly what a whole segment must contain. */
        val framesPerSegment: Int,
    ) {
        /**
         * How many AAC frames segment [index] must actually hold to match what this playlist
         * promised a player. Used to verify encoder output before it is served — see [AdtsFrames].
         *
         * Whole segments are exact: measured against real FFmpeg, every one is precisely
         * [framesPerSegment]. Only the final segment gets slack, and only one frame of it, because
         * the encoder pads its last frame out to a whole 1024 samples.
         */
        fun expectedFrames(index: Int): IntRange {
            if (index !in segmentDurations.indices) return IntRange.EMPTY
            if (index < segmentDurations.lastIndex) return framesPerSegment..framesPerSegment
            val tail = ceil(segmentDurations.last() * sampleRate / SAMPLES_PER_FRAME).toInt()
            return tail..(tail + 1)
        }
    }

    /**
     * Segments [durationMs] of audio at [sampleRate] into frame-aligned chunks of about
     * [targetSeconds]. The last segment is short — it carries whatever remains.
     */
    fun plan(
        durationMs: Long,
        sampleRate: Int?,
        targetSeconds: Int,
    ): Plan {
        val rate = sampleRate?.takeIf { it > 0 } ?: FALLBACK_SAMPLE_RATE
        val framesPerSegment = ceil(targetSeconds.toDouble() * rate / SAMPLES_PER_FRAME).toInt()
        val segmentSeconds = framesPerSegment.toDouble() * SAMPLES_PER_FRAME / rate
        val total = durationMs / 1000.0

        val whole = (total / segmentSeconds).toInt()
        val remainder = total - whole * segmentSeconds
        val durations = MutableList(whole) { segmentSeconds }
        if (remainder > 0.0) durations += remainder

        return Plan(segmentSeconds, durations, rate, framesPerSegment)
    }

    /**
     * Renders the one-variant master playlist pointing at [mediaUrl].
     *
     * There is exactly one rendition, so a master is strictly speaking optional — it exists because
     * some players (Safari most notably) behave better when handed one, and because a second
     * rendition later becomes an added line here rather than a new shape.
     */
    fun renderMaster(
        mediaUrl: String,
        bitrateKbps: Int,
    ): String =
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-STREAM-INF:BANDWIDTH=${bitrateKbps * BITS_PER_KBIT},CODECS=\"$AAC_LC_CODEC_TAG\"")
            append(mediaUrl)
        }

    /** Renders [plan] as a VOD `.m3u8`, asking [segmentUrl] for each segment's (signed) URL. */
    fun render(
        plan: Plan,
        segmentUrl: (Int) -> String,
    ): String =
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-TARGETDURATION:${ceil(plan.segmentSeconds).toInt()}")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            plan.segmentDurations.forEachIndexed { index, seconds ->
                appendLine("#EXTINF:${formatSeconds(seconds)},")
                appendLine(segmentUrl(index))
            }
            append("#EXT-X-ENDLIST")
        }

    /**
     * Six decimal places — enough that per-segment rounding cannot accumulate across 33,000 of them.
     *
     * Shared with [TranscodeSessionEngine], which must hand FFmpeg the *same* text this playlist
     * declares: a `-ss` or `-segment_time` that disagrees with an `#EXTINF` is the drift this whole
     * file exists to prevent, reintroduced one layer down.
     */
    internal fun formatSeconds(seconds: Double): String {
        val scaled = (seconds * MICROS_PER_SECOND).toLong()
        val fraction = (scaled % MICROS_PER_SECOND).toString().padStart(FRACTION_DIGITS, '0')
        return "${scaled / MICROS_PER_SECOND}.$fraction"
    }
}
