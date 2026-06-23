package com.calypsan.listenup.server.embeddedmeta.fixtures

/**
 * Builders for the audio sample-entry bytes consumed by
 * [com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4CodecExtractor],
 * plus [buildAudioMoov] which wraps an entry in the minimal box tree the
 * extractor walks: `moov → (mvhd) + trak → mdia → {hdlr "soun"} + minf →
 * stbl → stsd(entry_count=1, entry)`.
 *
 * Each entry is a real MP4 box: `size(4 BE) + fourcc(4) + AudioSampleEntry
 * header(28) + codec-specific child box(es)`. The 28-byte AudioSampleEntry
 * header layout (relative to the box payload start) is fixed by ISO/IEC
 * 14496-12 §12.2.3 / QuickTime:
 * ```
 * [0..5]   reserved
 * [6..7]   data_reference_index
 * [8..9]   version          [10..11] revision        [12..15] vendor
 * [16..17] channelcount     [18..19] samplesize      [20..21] pre_defined
 * [22..23] reserved         [24..27] samplerate (16.16 fixed; hi 16 = Hz)
 * ```
 * These fixtures are self-consistent with the extractor: the bytes written
 * here are read back at the same offsets there. Where a codec box layout is
 * a best-effort convention (notably `dec3` JOC detection), the fixture and
 * extractor agree on the documented convention and the test pins it; Task 8
 * validates against a real Atmos file and may refine.
 */

private const val SAMPLE_ENTRY_HEADER_SIZE = 28

private fun be16(value: Int): ByteArray = byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

private fun be32(value: Int): ByteArray =
    byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

/**
 * Build the 28-byte AudioSampleEntry header for a [channels]-channel,
 * [sampleRate]-Hz entry. samplerate is encoded as a 16.16 fixed-point value
 * whose high 16 bits hold the integer Hz (fractional part zero) — matching how
 * the extractor reads `sampleRate = readBeUInt16(..., dataOffset+24)`.
 */
private fun sampleEntryHeader(
    channels: Int,
    sampleRate: Int,
): ByteArray {
    val out = ByteArray(SAMPLE_ENTRY_HEADER_SIZE)
    // [0..5] reserved, [6..7] data_reference_index = 1.
    be16(1).copyInto(out, 6)
    // [8..15] version/revision/vendor stay zero.
    be16(channels).copyInto(out, 16) // [16..17] channelcount
    be16(16).copyInto(out, 18) // [18..19] samplesize (16-bit PCM convention)
    // [20..23] pre_defined + reserved stay zero.
    be16(sampleRate).copyInto(out, 24) // [24..25] samplerate integer Hz; [26..27] fraction = 0
    return out
}

/** Wrap a fourcc + header + codec-specific bytes into a sample-entry box. */
private fun sampleEntry(
    fourcc: String,
    channels: Int,
    sampleRate: Int,
    codecSpecific: ByteArray,
): ByteArray = atom(fourcc, sampleEntryHeader(channels, sampleRate) + codecSpecific)

// ---- esds (ES_Descriptor) construction, mirrors EsdsParserTest ----

private fun descriptor(
    tag: Int,
    body: ByteArray,
): ByteArray = byteArrayOf(tag.toByte(), body.size.toByte()) + body

/** Pack AOT(5) | freqIdx(4) | chanCfg(4) MSB-first into an AudioSpecificConfig prefix. */
private fun audioSpecificConfig(
    aot: Int,
    freqIdx: Int,
    chan: Int,
): ByteArray {
    var v = 0L
    var n = 0

    fun put(
        value: Int,
        bits: Int,
    ) {
        v = (v shl bits) or value.toLong()
        n += bits
    }
    if (aot >= 31) {
        put(31, 5)
        put(aot - 32, 6)
    } else {
        put(aot, 5)
    }
    put(freqIdx, 4)
    put(chan, 4)
    val totalBytes = (n + 7) / 8
    v = v shl totalBytes * 8 - n
    return ByteArray(totalBytes) { i -> ((v ushr (totalBytes - 1 - i) * 8) and 0xFF).toByte() }
}

private fun esdsBox(
    objectTypeIndication: Int,
    avgBitrate: Int,
    asc: ByteArray?,
): ByteArray {
    val decoderSpecific = if (asc != null) descriptor(0x05, asc) else ByteArray(0)
    val decoderConfigBody =
        byteArrayOf(objectTypeIndication.toByte(), 0x15) + // oti, streamType/upstream/reserved
            byteArrayOf(0, 0, 0) + // bufferSizeDB(3)
            be32(0) + // maxBitrate
            be32(avgBitrate) +
            decoderSpecific
    val decoderConfig = descriptor(0x04, decoderConfigBody)
    val esBody = byteArrayOf(0, 1, 0) + decoderConfig // ES_ID(2)=1, flags(1)=0
    val es = descriptor(0x03, esBody)
    return atom("esds", byteArrayOf(0, 0, 0, 0) + es) // FullBox version+flags + ES_Descriptor
}

/**
 * Build an `mp4a` sample entry. When [ascAot] is non-null an `esds` carrying an
 * AAC AudioSpecificConfig (AOT/freqIdx/chanCfg) is appended; otherwise the
 * `esds` carries no DecoderSpecificInfo and the extractor falls back to the
 * sample-entry header for sampleRate/channels.
 */
internal fun mp4aEntry(
    channels: Int,
    sampleRate: Int,
    esdsAvgBitrate: Int = 0,
    ascAot: Int? = null,
    ascFreqIdx: Int = 0,
    ascChan: Int = 0,
): ByteArray {
    val asc = ascAot?.let { audioSpecificConfig(it, ascFreqIdx, ascChan) }
    val esds = esdsBox(objectTypeIndication = 0x40, avgBitrate = esdsAvgBitrate, asc = asc)
    return sampleEntry("mp4a", channels, sampleRate, esds)
}

/** Build an `ac-4` sample entry. The extractor reads only the entry header; `dac4` is opaque. */
internal fun ac4Entry(
    channels: Int,
    sampleRate: Int,
): ByteArray = sampleEntry("ac-4", channels, sampleRate, atom("dac4", byteArrayOf(0x01, 0x02, 0x03)))

/**
 * Build an `ec-3` sample entry. JOC (Dolby Atmos object coding) presence is
 * signalled — per the convention pinned by this fixture + the extractor — by
 * the lowest bit of the `dec3` payload's final byte. Task 8 refines this best
 * effort against a real file.
 */
internal fun ec3Entry(
    channels: Int,
    sampleRate: Int,
    joc: Boolean,
): ByteArray {
    val lastByte = if (joc) 0x01 else 0x00
    val dec3 = atom("dec3", byteArrayOf(0x00, 0x00, lastByte.toByte()))
    return sampleEntry("ec-3", channels, sampleRate, dec3)
}

// ---- moov box tree assembly ----

private fun hdlrSoun(): ByteArray {
    // hdlr FullBox: version+flags(4) + pre_defined(4) + handler_type(4) + reserved(12) + name(1).
    val versionFlagsAndPreDefined = ByteArray(8)
    val handlerType = "soun".toByteArray(Charsets.US_ASCII)
    val payload = versionFlagsAndPreDefined + handlerType + ByteArray(12) + byteArrayOf(0)
    return atom("hdlr", payload)
}

// stsd FullBox: version+flags(4) + entry_count(4) + the single entry.
private fun stsd(entry: ByteArray): ByteArray = atom("stsd", byteArrayOf(0, 0, 0, 0) + be32(1) + entry)

private fun audioTrak(entry: ByteArray): ByteArray {
    val stbl = atom("stbl", stsd(entry))
    val minf = atom("minf", stbl)
    val mdia = atom("mdia", hdlrSoun() + minf)
    return atom("trak", mdia)
}

private fun minimalMvhd(): ByteArray {
    // mvhd v0: version+flags(4) + creation(4) + modification(4) + timescale(4) + duration(4) + 80 trailing bytes.
    val out = byteArrayOf(0, 0, 0, 0) + be32(0) + be32(0) + be32(1000) + be32(0) + ByteArray(80)
    return atom("mvhd", out)
}

/**
 * Assemble a `moov` (returned as bytes, with `moov` at offset 0) carrying a
 * minimal `mvhd` and — when [audioEntry] is non-null — a single audio `trak`
 * wrapping that sample entry. Pass `audioEntry = null` to produce a moov with
 * no audio track (the extractor must then return null).
 */
internal fun buildAudioMoov(audioEntry: ByteArray?): ByteArray {
    val children = if (audioEntry != null) minimalMvhd() + audioTrak(audioEntry) else minimalMvhd()
    return atom("moov", children)
}
