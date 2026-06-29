package com.calypsan.listenup.server.embeddedmeta.fixtures

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * DSL for building synthetic MP4 / M4A / M4B file bytes for parser tests.
 *
 * Reviewable in PR; deterministic; hermetic. Backs the fixture-driven
 * [com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4Parser] tests and
 * the property-test generators that exercise the parser on randomised inputs.
 *
 * The Nero `chpl` encoding is `version(1) + flags(3) + reserved(4) +
 * count(1) + per-chapter (start-100ns(8) + title-len(1) + title-utf8)`.
 *
 * Coverage:
 * - [Mp4Builder.ftyp] — file-type atom with configurable major brand
 * - [Mp4Builder.moov] — movie container with mvhd + tracks + udta
 * - [MoovBuilder.mvhd] — movie header (v0 32-bit duration; v1 promoted on overflow)
 * - [MoovBuilder.audioTrack] — minimal audio `trak` with optional `tref.chap`
 * - [MoovBuilder.chapterTextTrack] — Apple QuickTime chapter text-track
 * - [UdtaBuilder.meta] / [IlstBuilder] — `ilst` text tags, `covr` artwork, `----` freeform
 * - [UdtaBuilder.chpl] — Nero chapter list
 *
 * NOT covered (out-of-scope for tests): true audio data (a 0-byte `mdat` stub
 * is emitted), encrypted atoms, sidx, edts/elst, fragments. Add as needed
 * when a parser test demands them.
 */
internal fun buildMp4File(block: Mp4Builder.() -> Unit): ByteArray = Mp4Builder().apply(block).build()

internal class Mp4Builder internal constructor() {
    private var ftypAtom: ByteArray? = null
    private var moovAtom: ByteArray? = null

    // Minimal mdat stub — 8-byte header + zero-byte payload. The parser
    // skips mdat entirely, so this never needs real audio bytes.
    private var mdatAtom: ByteArray = atom("mdat", ByteArray(0))

    /**
     * Emit the file-type `ftyp` atom.
     *
     * [brand] is the 4-byte major brand ASCII string; common values are
     * `"M4A "` (M4A audio) and `"M4B "` (M4B audiobook). [compatibleBrands]
     * is the trailing brand list — defaults to `[brand, "isom"]`.
     */
    fun ftyp(
        brand: String = "M4B ",
        compatibleBrands: List<String> = listOf("M4B ", "isom"),
    ) {
        require(brand.length == 4) { "brand must be 4 ASCII chars (got '$brand')" }
        val out = Buffer()
        out.write(brand.toByteArray(Charsets.US_ASCII))
        out.writeBigEndianInt(0) // minor version
        for (cb in compatibleBrands) {
            require(cb.length == 4) { "compatible brand must be 4 ASCII chars (got '$cb')" }
            out.write(cb.toByteArray(Charsets.US_ASCII))
        }
        ftypAtom = atom("ftyp", out.readByteArray())
    }

    fun moov(block: MoovBuilder.() -> Unit) {
        moovAtom = MoovBuilder().apply(block).build()
    }

    fun build(): ByteArray {
        requireNotNull(ftypAtom) { "ftyp() must be invoked" }
        requireNotNull(moovAtom) { "moov { ... } must be invoked" }
        val out = Buffer()
        out.write(ftypAtom!!)
        out.write(moovAtom!!)
        out.write(mdatAtom)
        return out.readByteArray()
    }
}

internal class MoovBuilder internal constructor() {
    private val children = mutableListOf<ByteArray>()

    /**
     * Emit a `mvhd` (movie header) atom carrying the file timescale and
     * duration. Version 0 (32-bit duration) is used unless [durationInTimescale]
     * exceeds [Int.MAX_VALUE], in which case version 1 (64-bit duration) is
     * emitted automatically.
     */
    fun mvhd(
        timescale: Int,
        durationInTimescale: Long,
    ) {
        require(timescale > 0) { "timescale must be positive (got $timescale)" }
        require(durationInTimescale >= 0) { "duration must be non-negative (got $durationInTimescale)" }
        val needsV1 = durationInTimescale > Int.MAX_VALUE.toLong()
        val out = Buffer()
        if (needsV1) {
            out.writeByte(0x01) // version 1
            out.write(byteArrayOf(0, 0, 0)) // flags
            // 8-byte created + 8-byte modified
            out.writeBigEndianLong(0)
            out.writeBigEndianLong(0)
            out.writeBigEndianInt(timescale)
            out.writeBigEndianLong(durationInTimescale)
        } else {
            out.writeByte(0x00) // version 0
            out.write(byteArrayOf(0, 0, 0)) // flags
            // 4-byte created + 4-byte modified
            out.writeBigEndianInt(0)
            out.writeBigEndianInt(0)
            out.writeBigEndianInt(timescale)
            out.writeBigEndianInt(durationInTimescale.toInt())
        }
        // Trailing fixed fields the parser doesn't read — pad with zeros.
        // Real mvhd has rate(4)+volume(2)+reserved(10)+matrix(36)+pre_defined(24)+next_track_id(4) = 80 bytes.
        out.write(ByteArray(80))
        children += atom("mvhd", out.readByteArray())
    }

    /** Emit a `udta` user-data atom with the configured children. */
    fun udta(block: UdtaBuilder.() -> Unit) {
        children += UdtaBuilder().apply(block).build()
    }

    /**
     * Emit a minimal audio `trak`. The parser only reads the track ID and
     * optional `tref.chap` (which references a chapter text-track by id).
     *
     * When [sampleEntry] is non-null a real `mdia` tree is built:
     * `mdia → hdlr("soun") + minf → stbl → stsd(entry_count=1, entry)`.
     * The extractor requires this to navigate to the codec sample entry.
     *
     * When [sampleEntry] is null a zero-byte stub `mdia` is emitted — sufficient
     * for tests that only exercise tags, chapters, or duration.
     */
    fun audioTrack(
        trackId: Int = 1,
        chapterTrackRef: Int? = null,
        sampleEntry: ByteArray? = null,
    ) {
        val trakChildren = mutableListOf<ByteArray>()
        trakChildren += tkhd(trackId)
        if (chapterTrackRef != null) {
            val chapAtom = atom("chap", buildBigEndianIntList(listOf(chapterTrackRef)))
            trakChildren += atom("tref", chapAtom)
        }
        trakChildren +=
            if (sampleEntry != null) {
                // Real mdia with hdlr "soun" + minf/stbl/stsd so the codec
                // extractor can navigate to the sample entry.
                val hdlr = audioHdlr()
                val stsd = atom("stsd", byteArrayOf(0, 0, 0, 0) + be32(1) + sampleEntry)
                val stbl = atom("stbl", stsd)
                val minf = atom("minf", stbl)
                atom("mdia", hdlr + minf)
            } else {
                // Zero-byte stub — sufficient when the test doesn't exercise the extractor.
                atom("mdia", ByteArray(0))
            }
        children += atom("trak", trakChildren.flatten())
    }

    /**
     * Emit an Apple QuickTime chapter text-track. The parser walks
     * `trak/mdia/{mdhd, hdlr, minf/stbl/{stts, stsz, stco}}`. Sample data
     * lives inline in the same atom (single chunk, sequential samples) so
     * `stsc` is unnecessary.
     *
     * [timescale] is the per-track timescale; chapter [TextTrackChapter.startMs]
     * values are converted at emission time.
     */
    fun chapterTextTrack(
        trackId: Int,
        chapters: List<TextTrackChapter>,
        timescale: Int = 1000,
    ) {
        val trakChildren = mutableListOf<ByteArray>()
        trakChildren += tkhd(trackId)

        // Build sample data: each sample is a 2-byte big-endian length prefix +
        // UTF-8 title bytes.
        val samples =
            chapters.map { ch ->
                val titleBytes = ch.title.toByteArray(Charsets.UTF_8)
                require(titleBytes.size <= 0xFFFF) { "chapter title too long: ${titleBytes.size} bytes" }
                val out = Buffer()
                out.writeByte(((titleBytes.size shr 8) and 0xFF).toByte())
                out.writeByte((titleBytes.size and 0xFF).toByte())
                out.write(titleBytes)
                out.readByteArray()
            }
        val sampleSizes = samples.map { it.size }

        // Build mdia children.
        val mdiaChildren = mutableListOf<ByteArray>()
        mdiaChildren += mdhd(timescale)
        mdiaChildren += hdlr(handlerType = "text")

        // Build stbl with stts (sample timings), stsz (sample sizes), stco
        // (chunk offsets — single chunk, set during finalisation).
        val sampleDurations =
            chapters.mapIndexed { i, ch ->
                val nextStart =
                    if (i + 1 < chapters.size) chapters[i + 1].startMs else (ch.startMs + 1)
                val deltaMs = (nextStart - ch.startMs).coerceAtLeast(1)
                ((deltaMs * timescale) / 1000).toInt().coerceAtLeast(1)
            }

        val stbl =
            buildStbl(
                sampleSizes = sampleSizes,
                sampleDurations = sampleDurations,
                // Chunk offset is patched at finalisation since the absolute file
                // position depends on prior atoms. Use a sentinel here; we rebuild
                // the chunk-offset atom after laying out sample data.
                chunkOffsetSentinel = 0,
            )
        val minf = atom("minf", stbl)
        mdiaChildren += minf

        val mdia = atom("mdia", mdiaChildren.flatten())
        trakChildren += mdia

        val trak = atom("trak", trakChildren.flatten())
        // Sample data is concatenated and stored as a free-floating "samp"
        // pseudo-atom appended to moov children. The parser doesn't actually
        // need it to be a proper atom — it reads at the chunk offset directly
        // — but for atom-walker safety we wrap it.
        val sampleData = samples.flatten()
        children += trak
        children += sampleData
        // Track that we still need to patch the stco offset once we know the
        // moov layout. We do this in build() because chunk offsets are absolute.
        pendingTextTrackPatches +=
            TextTrackPatch(
                trakIndex = children.indexOf(trak),
                sampleDataIndex = children.indexOf(sampleData),
            )
    }

    private val pendingTextTrackPatches = mutableListOf<TextTrackPatch>()

    fun build(): ByteArray {
        // First lay out without patching; if there are no text-track patches,
        // we're done.
        if (pendingTextTrackPatches.isEmpty()) {
            return atom("moov", children.flatten())
        }
        // Otherwise we need to compute absolute chunk offsets. The full file
        // layout is: ftyp + moov + mdat. moov is what we're emitting here,
        // so chunk offset = ftypSize + (moov header + bytes-from-moov-start
        // to the sample data). Caller assembles into [Mp4Builder.build].
        // For a robust round-trip without complex patching, we emit a
        // co64 in stbl and rebuild stco later — but to keep the DSL simple,
        // we pre-compute offsets assuming the standard layout.

        // Pre-compute file layout: caller assembles ftyp + moov + mdat.
        // We don't know ftyp size from inside MoovBuilder, so we emit moov
        // with chunk offsets relative to the start of the FILE. The parser
        // reads chunkOffsets as absolute file offsets, so we add a placeholder
        // and re-encode at file-build time.
        //
        // Implementation note: we're inside MoovBuilder and don't have access
        // to ftyp size. The cleanest solution is to make Mp4Builder do the
        // patching after assembling all top-level atoms.
        //
        // Instead, encode moov with chunk offsets pre-set assuming a
        // canonical 24-byte ftyp (size 24 = ftyp header 8 + brand 4 + minor
        // 4 + 2 compat brands × 4 = 24). The default ftyp() emits exactly 24.
        // Tests using non-default ftyp lengths must use Nero chapters
        // (chpl) rather than text-track chapters, OR run text-track tests
        // through a known fixture size.
        val approximateFtypSize = DEFAULT_FTYP_BYTES
        // moov header is 8 bytes; sample-data offset within moov is determined
        // by all preceding bytes in `children`.
        var cumulative = approximateFtypSize.toLong() + 8L // ftyp + moov header
        val patchedChildren = children.toMutableList()
        for (patch in pendingTextTrackPatches) {
            // Sum bytes from start of moov children up to (but not including)
            // the sample-data slot.
            val bytesBeforeSamples = patchedChildren.take(patch.sampleDataIndex).sumOf { it.size }
            val sampleDataAbsoluteOffset = cumulative + bytesBeforeSamples
            // Patch the stco entry inside the trak atom.
            val patchedTrak =
                patchStcoOffset(patchedChildren[patch.trakIndex], sampleDataAbsoluteOffset.toInt())
            patchedChildren[patch.trakIndex] = patchedTrak
        }
        return atom("moov", patchedChildren.flatten())
    }

    /**
     * Emit an `hdlr` atom with `handler_type = "soun"` so the codec extractor
     * identifies this track as an audio track.
     *
     * Layout: version+flags(4) + pre_defined(4) + handler_type(4) + reserved(12) + name(1).
     */
    private fun audioHdlr(): ByteArray {
        val payload = ByteArray(8) + "soun".toByteArray(Charsets.US_ASCII) + ByteArray(13)
        return atom("hdlr", payload)
    }

    private fun tkhd(trackId: Int): ByteArray {
        val out = Buffer()
        out.writeBigEndianInt(0) // version + flags
        out.writeBigEndianInt(0) // creation
        out.writeBigEndianInt(0) // modification
        out.writeBigEndianInt(trackId)
        // Remaining tkhd fields: 4b reserved + 4b duration + 8b reserved +
        // 4b layer/altGroup + 4b volume/reserved + 36b matrix + 8b width/height
        // = 68 bytes. Parser only reads track id at offset +12 (v0).
        out.write(ByteArray(68))
        return atom("tkhd", out.readByteArray())
    }

    private fun mdhd(timescale: Int): ByteArray {
        // mdhd v0: version(1) + flags(3) + creation(4) + modification(4) +
        // timescale(4) + duration(4) + language(2) + pre_defined(2) = 24 bytes
        val out = Buffer()
        out.writeByte(0x00) // version 0
        out.write(byteArrayOf(0, 0, 0)) // flags
        out.writeBigEndianInt(0) // creation
        out.writeBigEndianInt(0) // modification
        out.writeBigEndianInt(timescale)
        out.writeBigEndianInt(0) // duration (parser doesn't use it)
        out.writeBigEndianInt(0) // language + pre_defined
        return atom("mdhd", out.readByteArray())
    }

    private fun hdlr(handlerType: String): ByteArray {
        require(handlerType.length == 4) { "handlerType must be 4 ASCII chars" }
        // hdlr: version+flags(4) + pre_defined(4) + handler_type(4) +
        //       reserved(12) + name(0-terminated UTF-8)
        val out = Buffer()
        out.writeBigEndianInt(0)
        out.writeBigEndianInt(0)
        out.write(handlerType.toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(12))
        out.writeByte(0) // empty name + null terminator
        return atom("hdlr", out.readByteArray())
    }

    private fun buildStbl(
        sampleSizes: List<Int>,
        sampleDurations: List<Int>,
        chunkOffsetSentinel: Int,
    ): ByteArray {
        val children = mutableListOf<ByteArray>()
        // Minimal stsd — single entry whose payload the parser doesn't read for
        // text tracks. Format: version+flags(4) + entry_count(4) + zero entries.
        val stsd =
            Buffer()
                .apply {
                    writeBigEndianInt(0)
                    writeBigEndianInt(0) // entry count
                }.readByteArray()
        children += atom("stsd", stsd)

        // stts: sample timings. Layout: version+flags(4) + entry_count(4) +
        // per entry: sample_count(4) + sample_delta(4).
        val sttsBuf = Buffer()
        sttsBuf.writeBigEndianInt(0)
        sttsBuf.writeBigEndianInt(sampleDurations.size)
        for (delta in sampleDurations) {
            sttsBuf.writeBigEndianInt(1) // sample count
            sttsBuf.writeBigEndianInt(delta)
        }
        children += atom("stts", sttsBuf.readByteArray())

        // stsz: sample sizes. version+flags(4) + sample_size(4 = 0 means
        // per-sample) + sample_count(4) + per-sample size(4).
        val stszBuf = Buffer()
        stszBuf.writeBigEndianInt(0)
        stszBuf.writeBigEndianInt(0) // 0 = per-sample sizes follow
        stszBuf.writeBigEndianInt(sampleSizes.size)
        for (s in sampleSizes) stszBuf.writeBigEndianInt(s)
        children += atom("stsz", stszBuf.readByteArray())

        // stco: chunk offsets. version+flags(4) + entry_count(4) + per chunk(4).
        val stcoBuf = Buffer()
        stcoBuf.writeBigEndianInt(0)
        stcoBuf.writeBigEndianInt(1) // single chunk
        stcoBuf.writeBigEndianInt(chunkOffsetSentinel)
        children += atom("stco", stcoBuf.readByteArray())

        return atom("stbl", children.flatten())
    }

    /**
     * Patch the absolute offset stored in the single `stco` entry inside the
     * given `trak` atom. Walks `trak/mdia/minf/stbl/stco` and overwrites the
     * 4-byte chunk-offset value at the known position.
     */
    private fun patchStcoOffset(
        trakBytes: ByteArray,
        newOffset: Int,
    ): ByteArray {
        val patched = trakBytes.copyOf()
        val stcoMarker = "stco".toByteArray(Charsets.US_ASCII)
        // stco payload layout: version+flags(4) + entry_count(4) + offset(4).
        // The 4-byte chunk offset starts 8 bytes after the 4-byte 'stco' magic.
        var i = 0
        while (i <= patched.size - stcoMarker.size) {
            if (
                patched[i] == stcoMarker[0] &&
                patched[i + 1] == stcoMarker[1] &&
                patched[i + 2] == stcoMarker[2] &&
                patched[i + 3] == stcoMarker[3]
            ) {
                val offsetPos = i + 4 + 4 + 4 // skip magic + version/flags + entry_count
                if (offsetPos + 4 <= patched.size) {
                    patched[offsetPos] = ((newOffset ushr 24) and 0xFF).toByte()
                    patched[offsetPos + 1] = ((newOffset ushr 16) and 0xFF).toByte()
                    patched[offsetPos + 2] = ((newOffset ushr 8) and 0xFF).toByte()
                    patched[offsetPos + 3] = (newOffset and 0xFF).toByte()
                    return patched
                }
            }
            i++
        }
        error("stco atom not found inside trak — DSL invariant violated")
    }

    private data class TextTrackPatch(
        val trakIndex: Int,
        val sampleDataIndex: Int,
    )
}

internal class UdtaBuilder internal constructor() {
    private val children = mutableListOf<ByteArray>()

    /**
     * Emit a `meta` atom containing an `ilst` (iTunes-style metadata list)
     * built via [block]. The 4-byte version+flags prefix on `meta` is added
     * automatically.
     */
    fun meta(block: IlstBuilder.() -> Unit) {
        val ilst = IlstBuilder().apply(block).build()
        // meta atom prefix: 4-byte version+flags before nested ilst.
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x00) + ilst
        children += atom("meta", payload)
    }

    /**
     * Emit a Nero `chpl` chapter list. Layout is
     * `version(1) + flags(3) + reserved(4) + count(1)` then per chapter
     * `start-100ns(8) + title-len(1) + title-utf8`.
     */
    fun chpl(
        version: Int = 1,
        chapters: List<NeroChapter>,
    ) {
        require(chapters.size <= 0xFF) { "chpl supports at most 255 chapters (got ${chapters.size})" }
        val out = Buffer()
        out.writeByte(version.toByte())
        out.write(byteArrayOf(0, 0, 0)) // flags
        out.writeBigEndianInt(0) // reserved
        out.writeByte(chapters.size.toByte()) // count
        for (chapter in chapters) {
            val startIn100Ns = chapter.startMs * 10_000
            out.writeBigEndianLong(startIn100Ns)
            val titleBytes = chapter.title.toByteArray(Charsets.UTF_8)
            require(titleBytes.size <= 0xFF) { "chpl title too long: ${titleBytes.size} bytes" }
            out.writeByte(titleBytes.size.toByte())
            out.write(titleBytes)
        }
        children += atom("chpl", out.readByteArray())
    }

    fun build(): ByteArray = atom("udta", children.flatten())
}

internal class IlstBuilder internal constructor() {
    private val tags = mutableListOf<ByteArray>()

    /**
     * Emit a standard text tag (e.g. `©nam`, `©ART`). [dataType] encodes the
     * data atom's type prefix per the iTunes spec — `1` for UTF-8 (default).
     */
    fun tag(
        atomType: String,
        value: String,
        dataType: Int = 1,
    ) {
        require(atomType.length == 4) { "iLst atom type must be 4 chars (got '$atomType')" }
        val dataPayload =
            Buffer()
                .apply {
                    writeBigEndianInt(dataType)
                    writeBigEndianInt(0) // locale
                    write(value.toByteArray(Charsets.UTF_8))
                }.readByteArray()
        tags += atom(atomType, atom("data", dataPayload))
    }

    /** Emit a `covr` cover-artwork atom. [mime] determines the data atom's type prefix. */
    fun cover(
        mime: String,
        bytes: ByteArray,
    ) {
        val dataType =
            when (mime) {
                "image/jpeg" -> 13
                "image/png" -> 14
                else -> error("only image/jpeg and image/png supported in test fixture (got $mime)")
            }
        val dataPayload =
            Buffer()
                .apply {
                    writeBigEndianInt(dataType)
                    writeBigEndianInt(0)
                    write(bytes)
                }.readByteArray()
        tags += atom("covr", atom("data", dataPayload))
    }

    /**
     * Emit a `----` reverse-DNS user-defined atom containing `mean`/`name`/
     * `data` children. Used for iTunes audiobook tags like
     * `com.apple.iTunes/Narrator`.
     */
    fun freeform(
        mean: String,
        name: String,
        value: String,
    ) {
        val meanAtom = atom("mean", byteArrayOf(0, 0, 0, 0) + mean.toByteArray(Charsets.UTF_8))
        val nameAtom = atom("name", byteArrayOf(0, 0, 0, 0) + name.toByteArray(Charsets.UTF_8))
        val dataPayload =
            Buffer()
                .apply {
                    writeBigEndianInt(1) // UTF-8
                    writeBigEndianInt(0) // locale
                    write(value.toByteArray(Charsets.UTF_8))
                }.readByteArray()
        val dataAtom = atom("data", dataPayload)
        tags += atom("----", meanAtom + nameAtom + dataAtom)
    }

    fun build(): ByteArray = atom("ilst", tags.flatten())
}

/** Nero `chpl` chapter entry: start time in ms relative to file start + UTF-8 title. */
internal data class NeroChapter(
    val startMs: Long,
    val title: String,
)

/** Apple text-track chapter entry: start time in ms relative to file start + UTF-8 title. */
internal data class TextTrackChapter(
    val startMs: Long,
    val title: String,
)

/**
 * Encode a single MP4 atom: 4-byte big-endian size + 4-byte ASCII type + payload.
 *
 * Atom type may contain non-ASCII bytes (e.g. `©nam` whose `©` is byte 0xA9),
 * so the type string is converted via ISO-8859-1 to preserve the byte values.
 */
internal fun atom(
    type: String,
    payload: ByteArray,
): ByteArray {
    require(type.length == 4) { "atom type must be 4 bytes (got '$type')" }
    val out = Buffer()
    val size = payload.size + 8
    out.writeBigEndianInt(size)
    out.write(type.toByteArray(Charsets.ISO_8859_1))
    out.write(payload)
    return out.readByteArray()
}

private fun List<ByteArray>.flatten(): ByteArray {
    val total = sumOf { it.size }
    val out = ByteArray(total)
    var offset = 0
    for (b in this) {
        b.copyInto(out, offset)
        offset += b.size
    }
    return out
}

private fun Buffer.writeBigEndianInt(value: Int) {
    writeByte(((value shr 24) and 0xFF).toByte())
    writeByte(((value shr 16) and 0xFF).toByte())
    writeByte(((value shr 8) and 0xFF).toByte())
    writeByte((value and 0xFF).toByte())
}

private fun Buffer.writeBigEndianLong(value: Long) {
    for (shift in 56 downTo 0 step 8) {
        writeByte(((value shr shift) and 0xFF).toByte())
    }
}

private fun be32(value: Int): ByteArray =
    byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

private fun buildBigEndianIntList(values: List<Int>): ByteArray {
    val out = Buffer()
    for (v in values) out.writeBigEndianInt(v)
    return out.readByteArray()
}

/**
 * Default-`ftyp` byte length: 8-byte header + 4-byte major brand +
 * 4-byte minor version + 2 × 4-byte compatible brands = 24 bytes.
 *
 * Used by [MoovBuilder.build] when patching absolute chunk offsets in
 * Apple text-track chapter tracks. Tests that need text-track chapters
 * should leave [Mp4Builder.ftyp] at its default arguments.
 */
private const val DEFAULT_FTYP_BYTES = 24
