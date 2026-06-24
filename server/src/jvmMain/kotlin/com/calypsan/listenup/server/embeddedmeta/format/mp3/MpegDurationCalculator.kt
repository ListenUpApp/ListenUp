
package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.server.embeddedmeta.SeekableAudioSource
import java.io.IOException

/**
 * Result of [MpegDurationCalculator.compute]: duration plus the technical
 * stream parameters decoded from the first MPEG frame header.
 *
 * [durationMs] is 0 when no valid MPEG frame could be located. [bitrate],
 * [sampleRate], and [channels] are null in that same case.
 */
internal data class MpegFrameInfo(
    val durationMs: Long,
    val bitrate: Int?,
    val sampleRate: Int?,
    val channels: Int?,
)

/**
 * Compute the audio duration of an MP3 file from its first MPEG frame header
 * and the size of the audio region.
 *
 * Strategy:
 * 1. Seek to [audioStart] (just past the ID3v2 tag, or 0 if no tag).
 * 2. Read a small sniff window and locate the first MPEG sync (`0xFFE` top
 *    11 bits) within it.
 * 3. Decode bitrate, sample rate, MPEG version, and channel mode from the
 *    4-byte frame header.
 * 4. Check for a Xing/Info VBR header at the version/channel-mode-dependent
 *    offset past the frame sync, or a VBRI header at the fixed +32 offset.
 *    If a frame-count field is present, derive duration from frame count ×
 *    1152 samples/frame ÷ sample-rate (exact for VBR).
 * 5. Fall back to CBR approximation when no VBR header is found:
 *    duration = audioBytes × 8 × 1000 / bitrate.
 *
 * Returns [MpegFrameInfo.durationMs] of 0 if no MPEG frame can be located in
 * the sniff window or the frame header is invalid — the parser still surfaces
 * tags successfully; only duration and stream params are unknown.
 *
 * VBR header offsets (byte offset from start of frame header):
 * - Xing/Info: 4 + sideInfoSize, where sideInfoSize depends on MPEG version
 *   and channel mode:
 *     MPEG-1 Stereo/JS/Dual → 32  → Xing at offset 36
 *     MPEG-1 Mono           → 17  → Xing at offset 21
 *     MPEG-2/2.5 Stereo/JS/Dual → 17 → Xing at offset 21
 *     MPEG-2/2.5 Mono       → 9   → Xing at offset 13
 * - VBRI: fixed offset 32 (produced by Fraunhofer encoder).
 *
 * MagicNumber suppressed: MPEG-1 Layer III frame-header field widths, the
 * 11-bit `0xFFE` sync mask, side-information sizes, the Xing/VBRI field
 * offsets, and the samples-per-frame constant (1152) are fixed by
 * ISO/IEC 11172-3 and the Fraunhofer/Xing VBR specifications.
 */
@Suppress("MagicNumber")
internal object MpegDurationCalculator {
    fun compute(
        source: SeekableAudioSource,
        audioStart: Long,
        hasV1Footer: Boolean,
    ): MpegFrameInfo {
        val noFrame = MpegFrameInfo(durationMs = 0, bitrate = null, sampleRate = null, channels = null)
        if (audioStart < 0 || audioStart >= source.length) return noFrame
        val sniffSize = minOf(SNIFF_WINDOW_BYTES.toLong(), source.length - audioStart).toInt()
        if (sniffSize < 4) return noFrame
        val prefix =
            try {
                source.seek(audioStart)
                source.readFully(sniffSize)
            } catch (_: IOException) {
                return noFrame
            }
        val syncInPrefix = locateMpegSync(prefix) ?: return noFrame
        val frame = decodeFrameHeader(prefix, syncInPrefix) ?: return noFrame

        // VBR path: check for Xing/Info or VBRI header in the sniff window.
        val durationMs =
            vbrDurationMs(prefix, syncInPrefix, frame) ?: run {
                // CBR fallback: estimate from audio-region byte count.
                val syncFileOffset = audioStart + syncInPrefix
                val footer = if (hasV1Footer) ID3V1_LEN.toLong() else 0L
                val audioBytes = source.length - syncFileOffset - footer
                if (audioBytes <= 0) return noFrame
                audioBytes * 8 * 1000 / frame.bitrate
            }
        return MpegFrameInfo(
            durationMs = durationMs,
            bitrate = frame.bitrate,
            sampleRate = frame.sampleRate,
            channels = frame.channels,
        )
    }

    /**
     * Returns the exact VBR duration in milliseconds if a Xing/Info or VBRI
     * header is present in [prefix] at [syncOffset], or `null` if neither
     * header is found and the caller should fall back to CBR estimation.
     */
    private fun vbrDurationMs(
        prefix: ByteArray,
        syncOffset: Int,
        frame: FrameHeader,
    ): Long? = xingDurationMs(prefix, syncOffset, frame) ?: vbriDurationMs(prefix, syncOffset, frame)

    /**
     * Parses a Xing or Info VBR header at the version/channel-mode-dependent
     * offset past [syncOffset] in [prefix]. Returns the frame-count-derived
     * duration in milliseconds, or `null` if no valid Xing/Info header is found.
     *
     * Xing offset = 4 (frame header) + sideInfoSize (17 or 32 bytes depending
     * on MPEG version and channel mode).
     */
    private fun xingDurationMs(
        prefix: ByteArray,
        syncOffset: Int,
        frame: FrameHeader,
    ): Long? {
        val xingOffset = syncOffset + 4 + frame.sideInfoSize
        if (xingOffset + 12 > prefix.size) return null
        val tag = String(prefix, xingOffset, 4, Charsets.ISO_8859_1)
        if (tag != "Xing" && tag != "Info") return null
        val flags = readInt32BE(prefix, xingOffset + 4)
        // Bit 0: frames field is present.
        if (flags and 0x0001 == 0) return null
        val frameCount = readInt32BE(prefix, xingOffset + 8).toLong()
        if (frameCount <= 0) return null
        return frameCount * SAMPLES_PER_FRAME * 1000L / frame.sampleRate
    }

    /**
     * Parses a VBRI header (Fraunhofer encoder) at the fixed offset of 32 bytes
     * past [syncOffset] in [prefix]. Returns the frame-count-derived duration in
     * milliseconds, or `null` if no valid VBRI header is found.
     *
     * VBRI layout: tag(4) + version(2) + delay(2) + quality(2) + bytes(4) + frames(4).
     * Frame count is at byte offset 14 within the VBRI block.
     */
    private fun vbriDurationMs(
        prefix: ByteArray,
        syncOffset: Int,
        frame: FrameHeader,
    ): Long? {
        val vbriOffset = syncOffset + 32
        if (vbriOffset + 18 > prefix.size) return null
        val tag = String(prefix, vbriOffset, 4, Charsets.ISO_8859_1)
        if (tag != "VBRI") return null
        val frameCount = readInt32BE(prefix, vbriOffset + 14).toLong()
        if (frameCount <= 0) return null
        return frameCount * SAMPLES_PER_FRAME * 1000L / frame.sampleRate
    }

    private data class FrameHeader(
        val bitrate: Int,
        val sampleRate: Int,
        /** Size of the MPEG side-information region in bytes. Used to locate
         *  the Xing/Info VBR header, which immediately follows the side info. */
        val sideInfoSize: Int,
        /** Channel count: 1 for mono (channel mode 3), 2 for all stereo families. */
        val channels: Int,
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

        // MPEG version: bits 20..19. 0b11=MPEG-1, 0b10=MPEG-2, 0b00=MPEG-2.5.
        val mpegVersion = (header ushr 19) and 0x3
        // Channel mode: bits 7..6. 0b11=Mono, else stereo-family (stereo/JS/dual-channel).
        val channelMode = (header ushr 6) and 0x3
        val isMono = channelMode == 3
        // Side-information size per ISO 11172-3 §2.4.3.1:
        //   MPEG-1 stereo=32, mono=17; MPEG-2/2.5 stereo=17, mono=9.
        val sideInfoSize =
            when {
                mpegVersion == 3 && !isMono -> 32

                // MPEG-1 stereo/JS/dual
                mpegVersion == 3 && isMono -> 17

                // MPEG-1 mono
                !isMono -> 17

                // MPEG-2/2.5 stereo/JS/dual
                else -> 9 // MPEG-2/2.5 mono
            }

        return FrameHeader(
            bitrate = bitrate,
            sampleRate = sampleRate,
            sideInfoSize = sideInfoSize,
            channels = if (isMono) 1 else 2,
        )
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

    /** Read a big-endian 32-bit unsigned integer from [buf] at [offset]. */
    private fun readInt32BE(
        buf: ByteArray,
        offset: Int,
    ): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)

    /** MPEG-1 Layer III bitrate table in kbps. */
    private val BITRATE_TABLE = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)

    /** MPEG-1 sample-rate table in Hz. */
    private val SAMPLE_RATE_TABLE = intArrayOf(44_100, 48_000, 32_000, 0)

    /** MPEG-1 Layer III: 1152 PCM samples per encoded audio frame. */
    private const val SAMPLES_PER_FRAME = 1152

    /**
     * 64 KB sniff window for the first MPEG sync byte. Real-world ID3v2 tags
     * declare their own size — there is no padding between tag and audio in
     * standard files. 64 KB leaves generous headroom for files with a short
     * non-standard gap; sync byte not found within this window → duration
     * reported as 0 (best-effort, parser still returns tags). Also large
     * enough to contain the VBR header that immediately follows the first
     * frame's side-information region.
     */
    private const val SNIFF_WINDOW_BYTES = 64 * 1024
    private const val ID3V1_LEN = 128
}
