
package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.server.embeddedmeta.SeekableAudioSource
import java.io.IOException

/**
 * Compute the audio duration of an MP3 file from its first MPEG frame header
 * and the size of the audio region.
 *
 * Strategy:
 * 1. Seek to [audioStart] (just past the ID3v2 tag, or 0 if no tag).
 * 2. Read a small sniff window and locate the first MPEG sync (`0xFFE` top
 *    11 bits) within it.
 * 3. Decode bitrate and sample rate from the 4-byte frame header.
 * 4. Compute audio-region size = `source.length - syncFileOffset - footerSize`.
 * 5. Duration = audioBytes * 8 / bitrate (CBR approximation).
 *
 * Returns `0` if no MPEG frame can be located in the sniff window or the
 * frame header is invalid — the parser still surfaces tags successfully;
 * only duration is unknown.
 *
 * TODO(VBR): Xing/VBRI VBR-header parsing is deferred. CBR is the common case
 * for audiobook MP3s; revisit when a VBR file surfaces in the live validation
 * harness. Reference: `/home/simonh/Code/audiometa/internal/mp3/technical.go`.
 */
// MPEG-1 Layer III frame-header field widths, the 11-bit `0xFFE` sync mask, and the
// bytes→bits×milliseconds duration arithmetic are fixed by ISO/IEC 11172-3.
@Suppress("MagicNumber")
internal object MpegDurationCalculator {
    fun compute(
        source: SeekableAudioSource,
        audioStart: Long,
        hasV1Footer: Boolean,
    ): Long {
        if (audioStart < 0 || audioStart >= source.length) return 0
        val sniffSize = minOf(SNIFF_WINDOW_BYTES.toLong(), source.length - audioStart).toInt()
        if (sniffSize < 4) return 0
        val prefix =
            try {
                source.seek(audioStart)
                source.readFully(sniffSize)
            } catch (_: IOException) {
                return 0
            }
        val syncInPrefix = locateMpegSync(prefix) ?: return 0
        val frame = decodeFrameHeader(prefix, syncInPrefix) ?: return 0

        val syncFileOffset = audioStart + syncInPrefix
        val footer = if (hasV1Footer) ID3V1_LEN.toLong() else 0L
        val audioBytes = source.length - syncFileOffset - footer
        if (audioBytes <= 0) return 0
        return audioBytes * 8 * 1000 / frame.bitrate
    }

    private data class FrameHeader(
        val bitrate: Int,
        val sampleRate: Int,
    )

    private fun decodeFrameHeader(
        prefix: ByteArray,
        syncOffset: Int,
    ): FrameHeader? {
        if (syncOffset + 4 > prefix.size) return null
        val header =
            ((prefix[syncOffset].toInt() and 0xFF) shl 24) or
                ((prefix[syncOffset + 1].toInt() and 0xFF) shl 16) or
                ((prefix[syncOffset + 2].toInt() and 0xFF) shl 8) or
                (prefix[syncOffset + 3].toInt() and 0xFF)
        val bitrateIdx = (header ushr 12) and 0xF
        val sampleRateIdx = (header ushr 10) and 0x3
        if (bitrateIdx <= 0 || bitrateIdx >= BITRATE_TABLE.size) return null
        if (sampleRateIdx >= SAMPLE_RATE_TABLE.size) return null
        val bitrate = BITRATE_TABLE[bitrateIdx] * 1000
        val sampleRate = SAMPLE_RATE_TABLE[sampleRateIdx]
        if (bitrate == 0 || sampleRate == 0) return null
        return FrameHeader(bitrate = bitrate, sampleRate = sampleRate)
    }

    private fun locateMpegSync(bytes: ByteArray): Int? {
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                return i
            }
            i++
        }
        return null
    }

    /** MPEG-1 Layer III bitrate table in kbps. */
    private val BITRATE_TABLE = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)

    /** MPEG-1 sample-rate table in Hz. */
    private val SAMPLE_RATE_TABLE = intArrayOf(44_100, 48_000, 32_000, 0)

    /**
     * 64 KB sniff window for the first MPEG sync byte. Real-world ID3v2 tags
     * declare their own size — there is no padding between tag and audio in
     * standard files. 64 KB leaves generous headroom for files with a short
     * non-standard gap; sync byte not found within this window → duration
     * reported as 0 (best-effort, parser still returns tags).
     */
    private const val SNIFF_WINDOW_BYTES = 64 * 1024
    private const val ID3V1_LEN = 128
}
