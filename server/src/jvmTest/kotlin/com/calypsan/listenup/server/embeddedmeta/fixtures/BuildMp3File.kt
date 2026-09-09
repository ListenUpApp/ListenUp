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
 * - [Mp3Builder.xingVbrFrames] — emit a single MPEG frame with a Xing/Info VBR header
 *   declaring a known frame count (all remaining bytes zeroed so VBR path is exercised)
 * - [Mp3Builder.vbriVbrFrames] — emit a single MPEG frame with a VBRI header
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
     * Emit N Layer III audio frames sized to approximate [durationSeconds]
     * at constant [bitrate] bps and [sampleRate] Hz. Each frame is its 4-byte
     * header followed by zeroed payload bytes — the parser only inspects the
     * header bits for bitrate / sample rate / channel mode and counts the
     * frames for duration.
     *
     * Frame geometry follows [version]: MPEG-1 is 1152 samples per frame and
     * `144 × bitrate ÷ sampleRate` bytes; MPEG-2 and MPEG-2.5 halve both
     * (576 samples, coefficient 72).
     */
    fun mpegFrames(
        durationSeconds: Int,
        bitrate: Int = 64_000,
        sampleRate: Int = 44_100,
        version: MpegVersion = MpegVersion.V1,
        mono: Boolean = false,
        layerBits: Int = LAYER_III_BITS,
    ) {
        require(durationSeconds >= 0) { "durationSeconds must be non-negative" }
        val frameSize = version.frameLengthCoefficient * bitrate / sampleRate
        require(frameSize >= 4) { "computed frame size $frameSize too small for 4-byte header" }
        val totalSamples = durationSeconds.toLong() * sampleRate
        val frameCount =
            (totalSamples / version.samplesPerFrame)
                .toInt()
                .coerceAtLeast(if (durationSeconds > 0) 1 else 0)
        val header =
            mpegFrameHeader(
                bitrate = bitrate,
                sampleRate = sampleRate,
                version = version,
                mono = mono,
                layerBits = layerBits,
            )
        val padding = ByteArray(frameSize - 4)
        repeat(frameCount) {
            out.write(header)
            out.write(padding)
        }
    }

    /**
     * Emit a single Layer III frame containing a Xing (VBR) or Info
     * (CBR-with-TOC) header that declares [frameCount] frames. The
     * remainder of the file is zeroed byte padding so the CBR formula on the
     * same bytes would produce a different (wrong) answer — thereby proving
     * that the VBR path engaged.
     *
     * The Xing/Info tag sits immediately past the frame's side-information
     * region, whose size depends on [version] and [mono] — see [sideInfoSize].
     *
     * @param tag       "Xing" for a true VBR file, "Info" for CBR-but-has-header.
     * @param frameCount declared frame count embedded in the Xing/Info header.
     * @param sampleRate sample rate to encode in the frame header.
     * @param bitrate    nominal bitrate to encode in the frame header (only
     *                   used to size the frame; the VBR path ignores it for
     *                   duration).
     * @param extraPaddingBytes extra zero bytes appended after the frame so
     *                   the CBR formula (file size / bitrate) produces a
     *                   measurably different duration from the VBR formula.
     * @param version   MPEG audio version to encode in the frame header.
     * @param mono      true to encode channel mode "mono", which also shrinks
     *                  the side-information region and moves the Xing tag.
     */
    @Suppress("MagicNumber")
    fun xingVbrFrames(
        frameCount: Int,
        tag: String = "Xing",
        sampleRate: Int = 44_100,
        bitrate: Int = 128_000,
        extraPaddingBytes: Int = 0,
        version: MpegVersion = MpegVersion.V1,
        mono: Boolean = false,
    ) {
        require(tag == "Xing" || tag == "Info") { "tag must be 'Xing' or 'Info'" }
        require(frameCount > 0) { "frameCount must be positive" }
        val frameHeader = mpegFrameHeader(bitrate = bitrate, sampleRate = sampleRate, version = version, mono = mono)
        val frameSize = version.frameLengthCoefficient * bitrate / sampleRate
        // Xing tag offset = 4 (frame header) + side info, exactly where the decoder looks.
        val xingOffset = 4 + sideInfoSize(version, mono)
        require(frameSize >= xingOffset + 12) { "frame too small to hold Xing header" }

        // Build the frame: 4-byte header + side info (zeroed) + Xing header + padding.
        val frame = ByteArray(frameSize)
        frameHeader.copyInto(frame)
        tag.toByteArray(Charsets.ISO_8859_1).copyInto(frame, xingOffset)
        // Flags = 0x0001: frames field present.
        frame[xingOffset + 4] = 0x00
        frame[xingOffset + 5] = 0x00
        frame[xingOffset + 6] = 0x00
        frame[xingOffset + 7] = 0x01
        // Frame count (big-endian 32-bit).
        frame[xingOffset + 8] = ((frameCount ushr 24) and 0xFF).toByte()
        frame[xingOffset + 9] = ((frameCount ushr 16) and 0xFF).toByte()
        frame[xingOffset + 10] = ((frameCount ushr 8) and 0xFF).toByte()
        frame[xingOffset + 11] = (frameCount and 0xFF).toByte()
        out.write(frame)
        if (extraPaddingBytes > 0) out.write(ByteArray(extraPaddingBytes))
    }

    /**
     * Emit a single Layer III stereo frame containing a VBRI header
     * (produced by the Fraunhofer encoder) that declares [frameCount] frames.
     *
     * The VBRI tag sits at fixed byte offset 32 from the start of the frame
     * (4-byte frame header + 28 bytes) — unlike Xing, that offset is fixed by
     * the Fraunhofer layout rather than derived from the side-information size,
     * so it does not move with [version] or the channel mode. VBRI layout
     * within its block: tag(4) + version(2) + delay(2) + quality(2) +
     * bytes(4) + frames(4).
     *
     * @param frameCount declared frame count.
     * @param sampleRate sample rate to encode in the frame header.
     * @param bitrate    nominal bitrate for the frame header.
     * @param version    MPEG audio version to encode in the frame header.
     */
    @Suppress("MagicNumber")
    fun vbriVbrFrames(
        frameCount: Int,
        sampleRate: Int = 44_100,
        bitrate: Int = 128_000,
        version: MpegVersion = MpegVersion.V1,
    ) {
        require(frameCount > 0) { "frameCount must be positive" }
        val frameHeader = mpegFrameHeader(bitrate = bitrate, sampleRate = sampleRate, version = version)
        val frameSize = version.frameLengthCoefficient * bitrate / sampleRate
        require(frameSize >= 32 + 18) { "frame too small to hold VBRI header" }

        val frame = ByteArray(frameSize)
        frameHeader.copyInto(frame)
        // VBRI tag at fixed offset 32 from the frame start.
        val vbriOffset = 32
        "VBRI".toByteArray(Charsets.ISO_8859_1).copyInto(frame, vbriOffset)
        // version (2 bytes, big-endian): 1
        frame[vbriOffset + 4] = 0x00
        frame[vbriOffset + 5] = 0x01
        // delay (2 bytes): 0
        frame[vbriOffset + 6] = 0x00
        frame[vbriOffset + 7] = 0x00
        // quality (2 bytes): 0
        frame[vbriOffset + 8] = 0x00
        frame[vbriOffset + 9] = 0x00
        // bytes (4 bytes): 0 (not needed for duration)
        frame[vbriOffset + 10] = 0x00
        frame[vbriOffset + 11] = 0x00
        frame[vbriOffset + 12] = 0x00
        frame[vbriOffset + 13] = 0x00
        // frames (4 bytes, big-endian): frameCount
        frame[vbriOffset + 14] = ((frameCount ushr 24) and 0xFF).toByte()
        frame[vbriOffset + 15] = ((frameCount ushr 16) and 0xFF).toByte()
        frame[vbriOffset + 16] = ((frameCount ushr 8) and 0xFF).toByte()
        frame[vbriOffset + 17] = (frameCount and 0xFF).toByte()
        out.write(frame)
    }

    fun build(): ByteArray = out.readByteArray()
}

/**
 * Build the 4 bytes of a Layer III frame header. Bit layout (MSB → LSB,
 * 32 bits across 4 big-endian bytes):
 *
 * | Bits  | Field           | Value used                                  |
 * |-------|-----------------|---------------------------------------------|
 * | 31..21| Sync (11 bits)  | `0b11111111111` (0xFFE)                     |
 * | 20..19| MPEG version    | [MpegVersion.versionBits]                   |
 * | 18..17| Layer           | [layerBits], default [LAYER_III_BITS]       |
 * | 16    | Protection      | `1` (no CRC follows)                        |
 * | 15..12| Bitrate index   | from [MpegVersion.bitrateTable]             |
 * | 11..10| Sample rate idx | from [MpegVersion.sampleRateTable]          |
 * | 9     | Padding         | `0`                                         |
 * | 8     | Private         | `0`                                         |
 * | 7..6  | Channel mode    | `0b11` when [mono], else `0b00` (stereo)    |
 * | 5..4  | Mode extension  | `0b00`                                      |
 * | 3     | Copyright       | `0`                                         |
 * | 2     | Original        | `0`                                         |
 * | 1..0  | Emphasis        | `0b00`                                      |
 *
 * This is the encode direction — the inverse of the frame-header decoder.
 */
internal fun mpegFrameHeader(
    bitrate: Int,
    sampleRate: Int,
    version: MpegVersion = MpegVersion.V1,
    mono: Boolean = false,
    layerBits: Int = LAYER_III_BITS,
): ByteArray {
    val bitrateIdx =
        version.bitrateTable.indexOf(bitrate / 1000).also {
            require(it > 0) { "bitrate $bitrate bps not in $version Layer III bitrate table" }
        }
    val sampleRateIdx =
        version.sampleRateTable.indexOf(sampleRate).also {
            require(it >= 0) { "sampleRate $sampleRate Hz not in $version sample-rate table" }
        }
    // Build 32-bit header
    var header = 0
    header = header or (0xFFE shl 20) // sync (11 bits) → bits 31..21
    header = header or (version.versionBits shl 19) // MPEG version → bits 20..19
    header = header or ((layerBits and 0x3) shl 17) // layer → bits 18..17
    header = header or (1 shl 16) // protection bit (no CRC) → bit 16
    header = header or ((bitrateIdx and 0xF) shl 12)
    header = header or ((sampleRateIdx and 0x3) shl 10)
    // A frame whose side-info size implies mono must also *say* mono, or it is not a valid file.
    if (mono) header = header or (0b11 shl 6) // channel mode → bits 7..6
    // padding=0, private=0, mode-ext=0, copyright=0, original=0, emphasis=0
    return byteArrayOf(
        ((header ushr 24) and 0xFF).toByte(),
        ((header ushr 16) and 0xFF).toByte(),
        ((header ushr 8) and 0xFF).toByte(),
        (header and 0xFF).toByte(),
    )
}

/**
 * Size in bytes of the side-information region that follows the 4-byte frame
 * header, per ISO/IEC 11172-3 §2.4.3.1 and the table in
 * [com.calypsan.listenup.server.embeddedmeta.format.mp3.MpegDurationCalculator]'s
 * KDoc: MPEG-1 stereo 32, MPEG-1 mono 17, MPEG-2/2.5 stereo 17, MPEG-2/2.5 mono 9.
 * A Xing/Info tag sits immediately past it.
 */
@Suppress("MagicNumber")
internal fun sideInfoSize(
    version: MpegVersion,
    mono: Boolean,
): Int =
    when {
        version == MpegVersion.V1 && !mono -> 32
        version == MpegVersion.V1 -> 17
        !mono -> 17
        else -> 9
    }

/**
 * Frame-header layer bits (18..17) for Layer III — the only layer this fixture
 * builder's tables describe. `0b10` is Layer II, `0b11` Layer I, `0b00` reserved;
 * a test emits those directly to prove the decoder declines them.
 */
internal const val LAYER_III_BITS: Int = 0b01

/** Layer III bitrate table in kbps, MPEG-1. Index 0 (free) and 15 (reserved) are invalid. */
internal val BITRATE_TABLE_V1: IntArray =
    intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)

/**
 * Layer III bitrate table in kbps, MPEG-2 (LSF) and MPEG-2.5 — the "low sampling
 * frequency" set. Index 0 (free) and 15 (reserved) are invalid.
 */
internal val BITRATE_TABLE_LSF: IntArray =
    intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

/** Sample-rate table in Hz, MPEG-1. Index 3 (reserved) is invalid. */
internal val SAMPLE_RATE_TABLE_V1: IntArray = intArrayOf(44_100, 48_000, 32_000, 0)

/** Sample-rate table in Hz, MPEG-2 — exactly half MPEG-1 at every index. */
internal val SAMPLE_RATE_TABLE_V2: IntArray = intArrayOf(22_050, 24_000, 16_000, 0)

/** Sample-rate table in Hz, MPEG-2.5 — exactly a quarter of MPEG-1 at every index. */
internal val SAMPLE_RATE_TABLE_V2_5: IntArray = intArrayOf(11_025, 12_000, 8_000, 0)

/**
 * The MPEG audio versions the fixture builder can emit, with their frame-header
 * bit pattern and the Layer III geometry and tables that go with it. Values are
 * from ISO/IEC 11172-3 (MPEG-1) and ISO/IEC 13818-3 (MPEG-2 LSF); MPEG-2.5 is
 * the de-facto Fraunhofer extension that reuses the MPEG-2 tables at a further
 * halved sample rate.
 */
@Suppress("MagicNumber")
internal enum class MpegVersion(
    val versionBits: Int,
    val samplesPerFrame: Int,
    val frameLengthCoefficient: Int,
    val bitrateTable: IntArray,
    val sampleRateTable: IntArray,
) {
    /** MPEG-1: bits 20..19 = 0b11, 1152 samples/frame, frame length = 144 × bitrate ÷ sampleRate. */
    V1(0b11, 1152, 144, BITRATE_TABLE_V1, SAMPLE_RATE_TABLE_V1),

    /** MPEG-2 (LSF): bits 20..19 = 0b10, 576 samples/frame, frame length = 72 × bitrate ÷ sampleRate. */
    V2(0b10, 576, 72, BITRATE_TABLE_LSF, SAMPLE_RATE_TABLE_V2),

    /** MPEG-2.5: bits 20..19 = 0b00, 576 samples/frame, same frame-length coefficient as MPEG-2. */
    V2_5(0b00, 576, 72, BITRATE_TABLE_LSF, SAMPLE_RATE_TABLE_V2_5),

    /**
     * Version bits 0b01 — reserved by ISO/IEC 11172-3, never valid in a real file.
     * Exists only so a test can emit a frame the decoder is required to decline;
     * its geometry and tables borrow the LSF values purely so the fixture can be
     * sized, and nothing ever decodes them.
     */
    RESERVED(0b01, 576, 72, BITRATE_TABLE_LSF, SAMPLE_RATE_TABLE_V2),
}

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
internal class Id3v2FrameSet internal constructor(
    private val version: Int,
) {
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

    /** COMM comments frame. Body = encoding + 3-byte language + shortDescription\0 + text. */
    fun commFrame(
        text: String,
        language: String = "eng",
        shortDescription: String = "",
    ) {
        val payload =
            byteArrayOf(0x03) +
                language.toByteArray(Charsets.ISO_8859_1).copyOf(3) +
                shortDescription.toByteArray(Charsets.UTF_8) + 0x00.toByte() +
                text.toByteArray(Charsets.UTF_8)
        frames += "COMM" to payload
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
