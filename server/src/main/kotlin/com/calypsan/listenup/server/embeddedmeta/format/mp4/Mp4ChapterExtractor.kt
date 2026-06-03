
package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.server.embeddedmeta.SeekableAudioSource

/**
 * Extracts chapter lists from MP4 files. Two encodings are supported:
 *
 * - **Nero `chpl`** (`moov.udta.chpl`): a flat list of `(start-100ns, title)`
 *   entries. End times are derived as `next.startMs - 1` with the last
 *   chapter clamped to the file's `durationMs`. Layout matches the Go
 *   reference `chapters.go`: `version(1) + flags(3) + reserved(4) +
 *   count(1) + per-entry`.
 * - **Apple QuickTime text-track** (`tref.chap` → text-track sample table):
 *   the audio track's `tref.chap` references a chapter `trak`, whose `mdia/
 *   minf/stbl` carries `stts` (per-sample timing), `stsz` (sample sizes),
 *   `stco` / `co64` (chunk offsets). Each text sample is `length(2 BE) +
 *   UTF-8 title`. Single-chunk layouts only — multi-chunk text tracks would
 *   need `stsc` parsing, which the Go reference also defers.
 *
 * Per spec §8.4 precedence rule, callers run [readNeroChpl] first; if it
 * yields any chapters, the Apple text-track path is skipped.
 */
// Nero `chpl` and Apple QuickTime text-track atom field offsets/sizes (chpl
// header layout, tkhd/mdhd version-dependent offsets, stts/stsz/stco entry
// widths, 100ns→ms scaling) are fixed by ISO/IEC 14496-12 and the Nero chapter
// format.
@Suppress("MagicNumber")
internal object Mp4ChapterExtractor {
    /**
     * Read Nero `chpl` chapters. Returns an empty list if `moov.udta.chpl`
     * is absent or contains zero entries.
     */
    fun readNeroChpl(
        bytes: ByteArray,
        moovAtom: Atom,
        durationMs: Long,
    ): List<Chapter> {
        val udta = AtomWalker.findChild(bytes, moovAtom.dataOffset, moovAtom.end, "udta") ?: return emptyList()
        val chpl = AtomWalker.findChild(bytes, udta.dataOffset, udta.end, "chpl") ?: return emptyList()

        // chpl layout: version(1) + flags(3) + reserved(4) + count(1) + entries.
        var p = chpl.dataOffset
        val end = chpl.end
        if (p + 9 > end) return emptyList()
        // val version = bytes[p].toInt() and 0xFF
        p += 1 // version
        p += 3 // flags
        p += 4 // reserved
        if (p >= end) return emptyList()
        val count = bytes[p].toInt() and 0xFF
        p += 1
        if (count == 0) return emptyList()

        val starts = mutableListOf<Pair<Long, String>>()
        for (i in 0 until count) {
            if (p + 8 > end) break
            val start100ns = AtomWalker.readBeInt64(bytes, p)
            p += 8
            if (p >= end) break
            val titleLen = bytes[p].toInt() and 0xFF
            p += 1
            if (p + titleLen > end) break
            val title =
                if (titleLen > 0) {
                    String(bytes, p, titleLen, Charsets.UTF_8)
                } else {
                    ""
                }
            p += titleLen
            starts += (start100ns / 10_000) to title
        }

        return buildChapters(starts, durationMs)
    }

    /**
     * Read Apple QuickTime text-track chapters. Resolves the chapter track
     * via the audio track's `tref.chap`, walks the chapter track's sample
     * table, and decodes each text sample.
     *
     * Sample text bytes live in the file's `mdat` at file-absolute offsets
     * recorded in `stco`/`co64`; [source] is used to read those tens-of-bytes
     * slices on demand rather than loading mdat into memory. [bytes] is the
     * pre-buffered moov payload only.
     *
     * Returns empty list if the file lacks any of the required atoms.
     */
    fun readAppleTextTrack(
        bytes: ByteArray,
        moovAtom: Atom,
        durationMs: Long,
        source: SeekableAudioSource,
    ): List<Chapter> {
        val chapterTrackId = findChapterTrackRef(bytes, moovAtom) ?: return emptyList()
        val chapterTrak = findTrackById(bytes, moovAtom, chapterTrackId) ?: return emptyList()
        return parseTextTrackChapters(bytes, chapterTrak, durationMs, source)
    }

    /** Walk every `trak` looking for one whose `tref.chap` carries a track id. */
    private fun findChapterTrackRef(
        bytes: ByteArray,
        moovAtom: Atom,
    ): Int? {
        var found: Int? = null
        AtomWalker.forEachChild(bytes, moovAtom.dataOffset, moovAtom.end) { atom ->
            if (atom.type != "trak" || found != null) return@forEachChild
            val tref = AtomWalker.findChild(bytes, atom.dataOffset, atom.end, "tref") ?: return@forEachChild
            val chap = AtomWalker.findChild(bytes, tref.dataOffset, tref.end, "chap") ?: return@forEachChild
            if (chap.dataSize < 4) return@forEachChild
            found = AtomWalker.readBeInt32(bytes, chap.dataOffset)
        }
        return found
    }

    /** Find the `trak` whose `tkhd` track_id equals [targetId]. */
    private fun findTrackById(
        bytes: ByteArray,
        moovAtom: Atom,
        targetId: Int,
    ): Atom? {
        var found: Atom? = null
        AtomWalker.forEachChild(bytes, moovAtom.dataOffset, moovAtom.end) { atom ->
            if (atom.type != "trak" || found != null) return@forEachChild
            val tkhd = AtomWalker.findChild(bytes, atom.dataOffset, atom.end, "tkhd") ?: return@forEachChild
            // tkhd v0: version(1) + flags(3) + creation(4) + modification(4) + track_id(4)
            // tkhd v1: version(1) + flags(3) + creation(8) + modification(8) + track_id(4)
            val version = bytes[tkhd.dataOffset].toInt() and 0xFF
            val trackIdOffset = if (version == 1) tkhd.dataOffset + 20 else tkhd.dataOffset + 12
            if (trackIdOffset + 4 > tkhd.end) return@forEachChild
            val tid = AtomWalker.readBeInt32(bytes, trackIdOffset)
            if (tid == targetId) found = atom
        }
        return found
    }

    private fun parseTextTrackChapters(
        bytes: ByteArray,
        trakAtom: Atom,
        durationMs: Long,
        source: SeekableAudioSource,
    ): List<Chapter> {
        val mdia = AtomWalker.findChild(bytes, trakAtom.dataOffset, trakAtom.end, "mdia") ?: return emptyList()
        val minf = AtomWalker.findChild(bytes, mdia.dataOffset, mdia.end, "minf") ?: return emptyList()
        val stbl = AtomWalker.findChild(bytes, minf.dataOffset, minf.end, "stbl") ?: return emptyList()

        val timescale = parseTrackTimescale(bytes, mdia)
        val sampleStartsMs = parseSampleStartsMs(bytes, stbl, timescale)
        val sampleSizes = parseSampleSizes(bytes, stbl)
        val chunkOffsets = parseChunkOffsets(bytes, stbl)

        if (sampleStartsMs.isEmpty() || sampleSizes.isEmpty() || chunkOffsets.isEmpty()) return emptyList()

        // Build absolute sample offsets. Single-chunk layout (the only one we
        // emit from the DSL and the only one Go's parser handles fully):
        // sample[i] = chunk[0] + sum(sampleSize[0..i-1]).
        val sampleOffsets = LongArray(sampleSizes.size)
        if (chunkOffsets.size == 1) {
            var cursor = chunkOffsets[0]
            for (i in sampleSizes.indices) {
                sampleOffsets[i] = cursor
                cursor += sampleSizes[i]
            }
        } else {
            // Multi-chunk fallback — assume 1:1 mapping (Go reference does
            // the same simplification, with the same caveat).
            for (i in 0 until minOf(chunkOffsets.size, sampleSizes.size)) {
                sampleOffsets[i] = chunkOffsets[i]
            }
        }

        val maxSamples = minOf(sampleStartsMs.size, sampleSizes.size, sampleOffsets.size)
        val starts = mutableListOf<Pair<Long, String>>()
        val sourceLength = source.length
        for (i in 0 until maxSamples) {
            val size = sampleSizes[i]
            if (size <= 0 || size >= 10_000) continue
            val absOffset = sampleOffsets[i]
            if (absOffset < 0 || absOffset + size > sourceLength) continue
            // Each text sample: length(2 BE) + UTF-8 title.
            if (size < 2) continue
            val sampleBytes =
                try {
                    source.seek(absOffset)
                    source.readFully(size)
                } catch (_: java.io.IOException) {
                    continue
                }
            val titleLen = ((sampleBytes[0].toInt() and 0xFF) shl 8) or (sampleBytes[1].toInt() and 0xFF)
            if (titleLen <= 0 || titleLen > size - 2) continue
            val title = String(sampleBytes, 2, titleLen, Charsets.UTF_8)
            starts += sampleStartsMs[i] to title
        }

        return buildChapters(starts, durationMs)
    }

    /** Read the per-track timescale from `mdia.mdhd`. */
    private fun parseTrackTimescale(
        bytes: ByteArray,
        mdiaAtom: Atom,
    ): Int {
        val mdhd = AtomWalker.findChild(bytes, mdiaAtom.dataOffset, mdiaAtom.end, "mdhd") ?: return 1000
        val version = bytes[mdhd.dataOffset].toInt() and 0xFF
        val tsOffset = if (version == 1) mdhd.dataOffset + 20 else mdhd.dataOffset + 12
        if (tsOffset + 4 > mdhd.end) return 1000
        val ts = AtomWalker.readBeInt32(bytes, tsOffset)
        return if (ts <= 0) 1000 else ts
    }

    /** Decode `stts` per-sample timings into millisecond start offsets. */
    private fun parseSampleStartsMs(
        bytes: ByteArray,
        stblAtom: Atom,
        timescale: Int,
    ): List<Long> {
        val stts = AtomWalker.findChild(bytes, stblAtom.dataOffset, stblAtom.end, "stts") ?: return emptyList()
        var p = stts.dataOffset + 4 // skip version+flags
        val end = stts.end
        if (p + 4 > end) return emptyList()
        val entryCount = AtomWalker.readBeInt32(bytes, p)
        p += 4
        val starts = mutableListOf<Long>()
        var cursorTimescaleUnits = 0L
        for (i in 0 until entryCount) {
            if (p + 8 > end) break
            val sampleCount = AtomWalker.readBeUInt32(bytes, p)
            val sampleDelta = AtomWalker.readBeUInt32(bytes, p + 4)
            p += 8
            for (j in 0 until sampleCount) {
                starts += (cursorTimescaleUnits * 1000L) / timescale.toLong()
                cursorTimescaleUnits += sampleDelta
            }
        }
        return starts
    }

    private fun parseSampleSizes(
        bytes: ByteArray,
        stblAtom: Atom,
    ): IntArray {
        val stsz = AtomWalker.findChild(bytes, stblAtom.dataOffset, stblAtom.end, "stsz") ?: return IntArray(0)
        var p = stsz.dataOffset + 4 // skip version+flags
        val end = stsz.end
        if (p + 8 > end) return IntArray(0)
        val defaultSize = AtomWalker.readBeInt32(bytes, p)
        p += 4
        val count = AtomWalker.readBeInt32(bytes, p)
        p += 4
        if (count <= 0) return IntArray(0)
        val out = IntArray(count)
        if (defaultSize != 0) {
            for (i in 0 until count) out[i] = defaultSize
            return out
        }
        for (i in 0 until count) {
            if (p + 4 > end) return out.copyOf(i)
            out[i] = AtomWalker.readBeInt32(bytes, p)
            p += 4
        }
        return out
    }

    private fun parseChunkOffsets(
        bytes: ByteArray,
        stblAtom: Atom,
    ): LongArray {
        val stco = AtomWalker.findChild(bytes, stblAtom.dataOffset, stblAtom.end, "stco")
        val co64 = if (stco == null) AtomWalker.findChild(bytes, stblAtom.dataOffset, stblAtom.end, "co64") else null
        val (atom, is64) =
            when {
                stco != null -> stco to false
                co64 != null -> co64 to true
                else -> return LongArray(0)
            }
        var p = atom.dataOffset + 4 // skip version+flags
        val end = atom.end
        if (p + 4 > end) return LongArray(0)
        val count = AtomWalker.readBeInt32(bytes, p)
        p += 4
        if (count <= 0) return LongArray(0)
        val out = LongArray(count)
        for (i in 0 until count) {
            if (is64) {
                if (p + 8 > end) return out.copyOf(i)
                out[i] = AtomWalker.readBeInt64(bytes, p)
                p += 8
            } else {
                if (p + 4 > end) return out.copyOf(i)
                out[i] = AtomWalker.readBeUInt32(bytes, p)
                p += 4
            }
        }
        return out
    }

    /**
     * Convert `(startMs, title)` pairs into [Chapter] objects with computed
     * `endMs`. Each chapter ends one millisecond before the next chapter's
     * start; the last chapter's end is clamped to [durationMs].
     */
    private fun buildChapters(
        starts: List<Pair<Long, String>>,
        durationMs: Long,
    ): List<Chapter> {
        if (starts.isEmpty()) return emptyList()
        val sorted = starts.sortedBy { it.first }
        return sorted.mapIndexed { i, (startMs, title) ->
            val endMs =
                if (i < sorted.size - 1) {
                    (sorted[i + 1].first - 1).coerceAtLeast(startMs)
                } else {
                    durationMs.coerceAtLeast(startMs)
                }
            Chapter(
                index = i + 1,
                title = title,
                startMs = startMs,
                endMs = endMs,
            )
        }
    }
}

/** Bundle returned by [Mp4ChapterExtractor.extract] — chapters + their source. */
internal data class Mp4ChapterResult(
    val chapters: List<Chapter>,
    val source: ChapterSource,
)

/**
 * Run the chapter precedence path on a parsed atom tree. Nero `chpl` wins
 * when present and non-empty; otherwise fall back to Apple text-track;
 * otherwise return an empty list with [ChapterSource.None].
 */
internal fun extractMp4Chapters(
    bytes: ByteArray,
    moovAtom: Atom,
    durationMs: Long,
    source: SeekableAudioSource,
): Mp4ChapterResult {
    val nero = Mp4ChapterExtractor.readNeroChpl(bytes, moovAtom, durationMs)
    if (nero.isNotEmpty()) return Mp4ChapterResult(nero, ChapterSource.Mp4Chpl)
    val apple = Mp4ChapterExtractor.readAppleTextTrack(bytes, moovAtom, durationMs, source)
    if (apple.isNotEmpty()) return Mp4ChapterResult(apple, ChapterSource.Mp4TextTrack)
    return Mp4ChapterResult(emptyList(), ChapterSource.None)
}
