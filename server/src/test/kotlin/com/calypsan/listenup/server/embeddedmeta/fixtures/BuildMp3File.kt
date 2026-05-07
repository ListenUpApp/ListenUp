@file:Suppress("MagicNumber") // Binary-format constants — readability beats named constants here.

package com.calypsan.listenup.server.embeddedmeta.fixtures

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * DSL for building synthetic MP3 file bytes for parser tests.
 *
 * Reviewable in PR; deterministic; hermetic. Backs the fixture-driven
 * [com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser] tests and
 * the property-test generators that exercise the parser on randomised inputs.
 *
 * Coverage:
 * - [Mp3Builder.id3v2] — emit an ID3v2.3 or 2.4 tag with a configurable frame set
 * - [Mp3Builder.id3v1] — emit a 128-byte ID3v1 footer (used for fallback tests)
 * - [Mp3Builder.mpegFrames] — emit N MPEG audio frames computed from a target duration
 *
 * NOT covered (out-of-scope for tests): true audio data, lyrics frames,
 * encrypted frames, VBR (Xing/VBRI) headers. Add as needed when a parser
 * test demands them.
 */
internal fun buildMp3File(block: Mp3Builder.() -> Unit): ByteArray = Mp3Builder().apply(block).build()

internal class Mp3Builder internal constructor() {
    private val out = Buffer()

    /** Emit an ID3v2.3 or ID3v2.4 tag containing the frames declared in [frames]. */
    fun id3v2(
        version: Int = 4,
        frames: Id3v2FrameSet.() -> Unit,
    ) {
        require(version in 3..4) { "ID3v2 versions 2.3/2.4 supported (got $version)" }
        val frameSet = Id3v2FrameSet(version).apply(frames)
        val frameBytes = frameSet.encode()
        // ID3v2 header: "ID3" + major + minor + flags + sync-safe size (4 bytes)
        out.write(byteArrayOf(0x49, 0x44, 0x33, version.toByte(), 0x00, 0x00))
        writeSyncSafeInt(out, frameBytes.size)
        out.write(frameBytes)
    }

    /**
     * Emit a 128-byte ID3v1 footer.
     *
     * Layout: "TAG" + 30b title + 30b artist + 30b album + 4b year + 30b comment + 1b genre.
     */
    fun id3v1(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ) {
        val payload = ByteArray(128)
        val tag = "TAG".toByteArray(Charsets.US_ASCII)
        tag.copyInto(payload, 0, 0, 3)
        title?.toByteArray(Charsets.US_ASCII)?.let {
            it.copyInto(payload, 3, 0, minOf(it.size, 30))
        }
        artist?.toByteArray(Charsets.US_ASCII)?.let {
            it.copyInto(payload, 33, 0, minOf(it.size, 30))
        }
        album?.toByteArray(Charsets.US_ASCII)?.let {
            it.copyInto(payload, 63, 0, minOf(it.size, 30))
        }
        out.write(payload)
    }

    /**
     * Emit N MPEG-1 Layer III audio frames sized to approximate [durationSeconds]
     * at constant [bitrate] bps and [sampleRate] Hz. Each frame is its 4-byte
     * header followed by zeroed payload bytes — the parser only inspects the
     * header bits for bitrate / sample rate / channel mode and counts the
     * frames for duration.
     *
     * MPEG-1 Layer III: 1152 samples per frame.
     */
    fun mpegFrames(
        durationSeconds: Int,
        bitrate: Int = 64_000,
        sampleRate: Int = 44_100,
    ) {
        require(durationSeconds >= 0) { "durationSeconds must be non-negative" }
        // Frame size in bytes for MPEG-1 Layer III: floor(144 * bitrate / sampleRate)
        val frameSize = (144 * bitrate) / sampleRate
        require(frameSize >= 4) { "computed frame size $frameSize too small for 4-byte header" }
        val samplesPerFrame = 1152
        val totalSamples = durationSeconds.toLong() * sampleRate
        val frameCount = (totalSamples / samplesPerFrame).toInt().coerceAtLeast(if (durationSeconds > 0) 1 else 0)
        val header = mpegFrameHeader(bitrate = bitrate, sampleRate = sampleRate)
        val padding = ByteArray(frameSize - 4)
        repeat(frameCount) {
            out.write(header)
            out.write(padding)
        }
    }

    fun build(): ByteArray = out.readByteArray()
}

/**
 * Build the 4 bytes of an MPEG-1 Layer III frame header. Bit layout (MSB → LSB,
 * 32 bits across 4 big-endian bytes):
 *
 * | Bits  | Field           | Value used                                  |
 * |-------|-----------------|---------------------------------------------|
 * | 31..21| Sync (11 bits)  | `0b11111111111` (0xFFE)                     |
 * | 20..19| MPEG version    | `0b11` (MPEG-1)                             |
 * | 18..17| Layer           | `0b01` (Layer III)                          |
 * | 16    | Protection      | `1` (no CRC follows)                        |
 * | 15..12| Bitrate index   | from [BITRATE_TABLE]                        |
 * | 11..10| Sample rate idx | 0=44100, 1=48000, 2=32000                   |
 * | 9     | Padding         | `0`                                         |
 * | 8     | Private         | `0`                                         |
 * | 7..6  | Channel mode    | `0b00` (stereo)                             |
 * | 5..4  | Mode extension  | `0b00`                                      |
 * | 3     | Copyright       | `0`                                         |
 * | 2     | Original        | `0`                                         |
 * | 1..0  | Emphasis        | `0b00`                                      |
 *
 * Ported from `/home/simonh/Code/audiometa/internal/mp3/technical.go` (the
 * reverse direction — that file decodes; this one encodes).
 */
internal fun mpegFrameHeader(
    bitrate: Int,
    sampleRate: Int,
): ByteArray {
    val bitrateIdx =
        BITRATE_TABLE.indexOf(bitrate / 1000).also {
            require(it > 0) { "bitrate $bitrate bps not in MPEG-1 Layer III bitrate table" }
        }
    val sampleRateIdx =
        SAMPLE_RATE_TABLE.indexOf(sampleRate).also {
            require(it >= 0) { "sampleRate $sampleRate Hz not in MPEG-1 sample-rate table" }
        }
    // Build 32-bit header
    var header = 0
    header = header or (0xFFE shl 20)              // sync (11 bits) → bits 31..21
    header = header or (0b11 shl 19)               // version MPEG-1 → bits 20..19
    header = header or (0b01 shl 17)               // layer III     → bits 18..17
    header = header or (1 shl 16)                  // protection bit (no CRC) → bit 16
    header = header or ((bitrateIdx and 0xF) shl 12)
    header = header or ((sampleRateIdx and 0x3) shl 10)
    // padding=0, private=0, channel=stereo(0b00), mode-ext=0, copyright=0, original=0, emphasis=0
    return byteArrayOf(
        ((header ushr 24) and 0xFF).toByte(),
        ((header ushr 16) and 0xFF).toByte(),
        ((header ushr 8) and 0xFF).toByte(),
        (header and 0xFF).toByte(),
    )
}

/** MPEG-1 Layer III bitrate table in kbps. Index 0 (free) and 15 (reserved) are invalid. */
internal val BITRATE_TABLE: IntArray =
    intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)

/** MPEG-1 sample-rate table in Hz. Index 3 (reserved) is invalid. */
internal val SAMPLE_RATE_TABLE: IntArray = intArrayOf(44_100, 48_000, 32_000, 0)

internal fun writeSyncSafeInt(
    buf: Buffer,
    value: Int,
) {
    require(value in 0..0x0FFFFFFF) { "sync-safe int max value is 0x0FFFFFFF (got $value)" }
    buf.writeByte(((value shr 21) and 0x7F).toByte())
    buf.writeByte(((value shr 14) and 0x7F).toByte())
    buf.writeByte(((value shr 7) and 0x7F).toByte())
    buf.writeByte((value and 0x7F).toByte())
}

/**
 * Frames inside an [Mp3Builder.id3v2] block.
 *
 * Encoding byte values used by [textFrame] / [txxxFrame] / [apicFrame] follow
 * the ID3v2 spec: `0x00`=ISO-8859-1, `0x01`=UTF-16 with BOM, `0x02`=UTF-16BE
 * (v2.4 only), `0x03`=UTF-8 (v2.4 only). Default is `0x03` (UTF-8).
 */
internal class Id3v2FrameSet internal constructor(private val version: Int) {
    private val frames = mutableListOf<Pair<String, ByteArray>>()

    /** Standard text frame (TIT2, TPE1, …). Body = encoding byte + UTF-8 text. */
    fun textFrame(
        id: String,
        value: String,
        encoding: Byte = 0x03,
    ) {
        require(id.length == 4) { "ID3v2 frame id must be 4 chars (got '$id')" }
        val payload = byteArrayOf(encoding) + value.toByteArray(Charsets.UTF_8)
        frames += id to payload
    }

    /** TXXX user-defined text frame. Body = encoding + description\0 + value. */
    fun txxxFrame(
        description: String,
        value: String,
    ) {
        val payload =
            byteArrayOf(0x03) +
                description.toByteArray(Charsets.UTF_8) + 0x00.toByte() +
                value.toByteArray(Charsets.UTF_8)
        frames += "TXXX" to payload
    }

    /**
     * APIC attached-picture frame.
     *
     * Body: encoding(1) + mime\0 + pictureType(1) + description\0 + imageBytes.
     * MIME is always ISO-8859-1 per spec.
     */
    fun apicFrame(
        mime: String,
        pictureType: Byte,
        description: String,
        imageBytes: ByteArray,
    ) {
        val payload =
            byteArrayOf(0x03) +
                mime.toByteArray(Charsets.ISO_8859_1) + 0x00.toByte() +
                byteArrayOf(pictureType) +
                description.toByteArray(Charsets.UTF_8) + 0x00.toByte() +
                imageBytes
        frames += "APIC" to payload
    }

    /**
     * CHAP chapter frame.
     *
     * Body: elementId\0 + startMs(4 BE) + endMs(4 BE) + startOffset(4) + endOffset(4)
     *       + optional embedded TIT2 sub-frame for the chapter title.
     *
     * `0xFFFFFFFF` for offsets means "ignore" per ID3v2 chapter-frame spec.
     */
    fun chapFrame(
        elementId: String,
        startMs: Int,
        endMs: Int,
        title: String? = null,
    ) {
        val out = Buffer()
        out.write(elementId.toByteArray(Charsets.ISO_8859_1))
        out.writeByte(0x00)
        out.writeInt(startMs)
        out.writeInt(endMs)
        out.writeInt(0xFFFFFFFF.toInt())
        out.writeInt(0xFFFFFFFF.toInt())
        if (title != null) {
            // Embedded TIT2 sub-frame — uses the same frame-header rules as the
            // outer tag: synchsafe size in v2.4, big-endian in v2.3.
            val sub = byteArrayOf(0x03) + title.toByteArray(Charsets.UTF_8)
            out.write("TIT2".toByteArray(Charsets.ISO_8859_1))
            writeFrameSize(out, sub.size)
            out.write(byteArrayOf(0x00, 0x00))
            out.write(sub)
        }
        frames += "CHAP" to out.readByteArray()
    }

    internal fun encode(): ByteArray {
        val out = Buffer()
        for ((id, payload) in frames) {
            out.write(id.toByteArray(Charsets.ISO_8859_1))
            writeFrameSize(out, payload.size)
            out.write(byteArrayOf(0x00, 0x00))
            out.write(payload)
        }
        return out.readByteArray()
    }

    private fun writeFrameSize(
        buf: Buffer,
        size: Int,
    ) {
        if (version == 4) {
            writeSyncSafeInt(buf, size)
        } else {
            buf.writeByte(((size shr 24) and 0xFF).toByte())
            buf.writeByte(((size shr 16) and 0xFF).toByte())
            buf.writeByte(((size shr 8) and 0xFF).toByte())
            buf.writeByte((size and 0xFF).toByte())
        }
    }
}
