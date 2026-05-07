@file:Suppress("MagicNumber") // MPEG bit-layout constants — readability beats names.

package com.calypsan.listenup.server.embeddedmeta.format.mp3

/**
 * Compute the audio duration of an MP3 file from its MPEG frame headers.
 *
 * Strategy:
 * 1. Locate the first MPEG sync (`0xFFE` top 11 bits) at or after [audioStart].
 * 2. Decode bitrate and sample rate from the frame header.
 * 3. Compute audio-region size = total - tagSize - id3v1FooterSize.
 * 4. Duration = audioBytes * 8 / bitrate (CBR approximation).
 *
 * Returns `0` if no MPEG frame can be located or the frame header is invalid
 * — the parser still surfaces tags successfully; only duration is unknown.
 *
 * TODO(VBR): Xing/VBRI VBR-header parsing is deferred. CBR is the common case
 * for audiobook MP3s; revisit when a VBR file surfaces in the live validation
 * harness. Reference: `/home/simonh/Code/audiometa/internal/mp3/technical.go`.
 */
internal object MpegDurationCalculator {
    fun compute(
        bytes: ByteArray,
        audioStart: Long,
    ): Long {
        if (audioStart < 0 || audioStart >= bytes.size) return 0
        val syncOffset = locateMpegSync(bytes, audioStart.toInt()) ?: return 0
        if (syncOffset + 4 > bytes.size) return 0
        val header =
            ((bytes[syncOffset].toInt() and 0xFF) shl 24) or
                ((bytes[syncOffset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[syncOffset + 2].toInt() and 0xFF) shl 8) or
                (bytes[syncOffset + 3].toInt() and 0xFF)
        val bitrateIdx = (header ushr 12) and 0xF
        val sampleRateIdx = (header ushr 10) and 0x3
        if (bitrateIdx <= 0 || bitrateIdx >= BITRATE_TABLE.size) return 0
        val bitrate = BITRATE_TABLE[bitrateIdx] * 1000
        if (sampleRateIdx >= SAMPLE_RATE_TABLE.size) return 0
        val sampleRate = SAMPLE_RATE_TABLE[sampleRateIdx]
        if (bitrate == 0 || sampleRate == 0) return 0

        val tail = if (hasId3v1Footer(bytes)) ID3V1_LEN else 0
        val audioBytes = bytes.size - syncOffset - tail
        if (audioBytes <= 0) return 0
        // CBR duration in ms: audioBytes * 8 / bitrate * 1000
        return (audioBytes.toLong() * 8 * 1000) / bitrate
    }

    private fun locateMpegSync(
        bytes: ByteArray,
        from: Int,
    ): Int? {
        var i = from
        while (i < bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                return i
            }
            i++
        }
        return null
    }

    private fun hasId3v1Footer(bytes: ByteArray): Boolean {
        if (bytes.size < ID3V1_LEN) return false
        val start = bytes.size - ID3V1_LEN
        return bytes[start] == 0x54.toByte() &&
            bytes[start + 1] == 0x41.toByte() &&
            bytes[start + 2] == 0x47.toByte()
    }

    /** MPEG-1 Layer III bitrate table in kbps. */
    private val BITRATE_TABLE = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)

    /** MPEG-1 sample-rate table in Hz. */
    private val SAMPLE_RATE_TABLE = intArrayOf(44_100, 48_000, 32_000, 0)

    private const val ID3V1_LEN = 128
}
