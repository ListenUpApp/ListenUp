package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.io.SeekableSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Adversarial memory-safety coverage for [Mp4Parser].
 *
 * Companion to `Mp3ParserAdversarialTest`. The Scanner Polish corpus is gone
 * — the 32 M4B "corrupt-header" files were genuinely corrupt and have been
 * cleaned up — but a malformed MP4 driving the JVM to `OutOfMemoryError` or
 * escaping the parser with an uncaught exception is a *robustness* defect
 * independent of any file. This suite proves structurally that no synthetic
 * malformed MP4 can do either: every declared-size field (the top-level
 * atom size, the 64-bit extended size, the `moov` size that gets narrowed
 * `Long -> Int`) is fed an adversarial value, and the parser must answer
 * with a typed [AppResult.Failure] — never a huge allocation, never a
 * thrown exception.
 *
 * Why a passing test *is* the proof: a "moov declares 5 GB in a 1 KB file"
 * input only survives if the guard rejects it before the
 * `readFully(topMoov.size.toInt())`. A missing guard would attempt the
 * multi-GB allocation and the test would OOM — so green here means the
 * guard held.
 *
 * Malformed bytes are hand-built because the fixture DSL
 * [com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp4File]
 * deliberately only emits *well-formed* atoms. [byteSource] is defined in
 * `Mp4ParserTest` (same package).
 */
class Mp4ParserAdversarialTest :
    FunSpec({
        val parser = Mp4Parser()

        /** Encode a big-endian 32-bit value into 4 bytes. */
        fun be32(value: Long): ByteArray =
            byteArrayOf(
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            )

        /** Encode a big-endian 64-bit value into 8 bytes. */
        fun be64(value: Long): ByteArray =
            byteArrayOf(
                ((value ushr 56) and 0xFF).toByte(),
                ((value ushr 48) and 0xFF).toByte(),
                ((value ushr 40) and 0xFF).toByte(),
                ((value ushr 32) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            )

        /** Build a standard 8-byte atom header: 4-byte BE size + 4-byte type. */
        fun atomHeader(
            type: String,
            size32: Long,
        ): ByteArray = be32(size32) + type.toByteArray(Charsets.US_ASCII)

        /** Wrap [payload] in a well-formed atom: 4-byte BE size (header + payload) + type + payload. */
        fun atom(
            type: String,
            payload: ByteArray,
        ): ByteArray = atomHeader(type, (payload.size + 8).toLong()) + payload

        /**
         * Build a self-contained top-level `moov` atom (no `ftyp` needed — [AtomWalker.findTopLevelAtom]
         * only scans for the requested type) with a valid `mvhd`, an audio `trak` referencing chapter
         * track id 2 via `tref.chap`, and a chapter text-track (`trak` id 2) whose `stbl` carries the
         * caller-supplied `stts`/`stsz`/`stco` payloads. Used to drive
         * [Mp4ChapterExtractor]'s three unbounded-count sites ([Mp4ChapterExtractor] KDoc:
         * `parseSampleStartsMs`/`parseSampleSizes`/`parseChunkOffsets`) with exactly one adversarial
         * count at a time — the atom framing itself stays well-formed (correct size fields) so the
         * walker descends correctly; only the count VALUE inside the targeted atom is malicious.
         */
        fun buildChapterTrackMoov(
            sttsPayload: ByteArray,
            stszPayload: ByteArray,
            stcoPayload: ByteArray,
            chunkOffsetType: String = "stco",
        ): ByteArray {
            // mvhd v0: version+flags(4) + creation(4) + modification(4) + timescale(4) + duration(4).
            val mvhd = atom("mvhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000) + be32(90_000))

            // Audio trak: tref.chap -> chapter track id 2. No tkhd needed — findChapterTrackRef
            // doesn't read it.
            val chap = atom("chap", be32(2))
            val tref = atom("tref", chap)
            val audioTrak = atom("trak", tref)

            // Chapter trak (id = 2): tkhd v0 (track_id at +12) + mdia(mdhd + minf/stbl).
            val tkhd = atom("tkhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(2))
            val mdhd = atom("mdhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000))
            val stts = atom("stts", sttsPayload)
            val stsz = atom("stsz", stszPayload)
            val stco = atom(chunkOffsetType, stcoPayload)
            val stbl = atom("stbl", stts + stsz + stco)
            val minf = atom("minf", stbl)
            val mdia = atom("mdia", mdhd + minf)
            val chapterTrak = atom("trak", tkhd + mdia)

            return atom("moov", mvhd + audioTrak + chapterTrak)
        }

        /**
         * Wrap [chapterTrakBody] as the chapter `trak` inside a `moov` that also carries a valid
         * `mvhd` (90 s at timescale 1000) and an audio `trak` whose `tref.chap` points at track
         * id 2. The chapter `trak` is the LAST child of `moov`, so whatever the caller puts last
         * inside [chapterTrakBody] also ends the buffer — which is how a test puts a box's
         * payload-free head exactly at the buffer's edge.
         */
        fun moovAroundChapterTrak(chapterTrakBody: ByteArray): ByteArray {
            val mvhd = atom("mvhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000) + be32(90_000))
            val audioTrak = atom("trak", atom("tref", atom("chap", be32(2))))
            return atom("moov", mvhd + audioTrak + atom("trak", chapterTrakBody))
        }

        /** `stts` payload with entryCount = 0 — benign, returns emptyList() with no loop. */
        fun benignSttsPayload(): ByteArray = ByteArray(4) + be32(0)

        /** `stsz` payload with count = 0 — benign, returns IntArray(0) with no allocation. */
        fun benignStszPayload(): ByteArray = ByteArray(4) + be32(0) + be32(0)

        /** `stco` payload with count = 0 — benign, returns LongArray(0) with no allocation. */
        fun benignStcoPayload(): ByteArray = ByteArray(4) + be32(0)

        /**
         * `stts` payload with one entry declaring sampleCount near Int.MAX_VALUE — the atom is only
         * 16 bytes long (version+flags + entryCount + one 8-byte entry), yet
         * `parseSampleStartsMs`'s inner `for (j in 0 until sampleCount)` would append ~2.1 billion
         * entries to a `MutableList<Long>` with no per-entry bytes to bound it.
         */
        fun maliciousSttsPayload(): ByteArray = ByteArray(4) + be32(1) + be32(0x7FFFFFFFL) + be32(1)

        /**
         * `stsz` payload declaring count near Int.MAX_VALUE with zero per-entry bytes present —
         * `parseSampleSizes` allocates `IntArray(count)` (~8 GB) before reading a single entry.
         */
        fun maliciousStszPayload(): ByteArray = ByteArray(4) + be32(0) + be32(0x7FFFFFFFL)

        /**
         * `stco` payload declaring count near Int.MAX_VALUE with zero per-entry bytes present —
         * `parseChunkOffsets` allocates `LongArray(count)` (~17 GB) before reading a single entry.
         */
        fun maliciousStcoPayload(): ByteArray = ByteArray(4) + be32(0x7FFFFFFFL)

        test("moov declaring a size far larger than the file is rejected, not allocated") {
            // A single top-level `moov` atom header that lies: it declares a
            // 4 GB size but the file is only the 8-byte header. The streaming
            // walker (`findTopLevelAtom`) checks `offset + size32 > length`
            // and returns null — so `moov` is never found and the parser
            // surfaces CorruptHeader without ever calling readFully(4 GB).
            val bytes = atomHeader("moov", size32 = 4L * 1024 * 1024 * 1024 - 1)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }

        test("moov whose declared size exceeds the 200 MB soft limit is rejected as CorruptHeader") {
            // A self-consistent file: a `moov` header declares a ~300 MB atom
            // and the file genuinely is that long. The streaming walker
            // accepts it (size fits the file), so the soft limit
            // `topMoov.size > MOOV_SOFT_LIMIT_BYTES` (200 MB) is the line of
            // defence — without it the parser would readFully ~300 MB.
            // Proven structurally: a synthetic source reports the length but
            // only holds the header; any readFully past it throws.
            val declaredMoovSize = 300L * 1024 * 1024 // 300 MB, over the 200 MB limit
            val header = atomHeader("moov", size32 = declaredMoovSize)
            val source = MoovHeaderOnlySource(header = header, claimedLength = declaredMoovSize)

            val result = runBlocking { parser.parse(source) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            val corrupt = failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
            // The soft-limit rejection message names the size budget.
            corrupt.expected.contains("sane size budget") shouldBe true
            // Proof: the parser never attempted the multi-hundred-MB readFully.
            (source.maxSingleReadBytes < 1024) shouldBe true
        }

        test("moov declared size at/over Int.MAX_VALUE exercises the Long to Int narrowing safely") {
            // `Mp4Parser` does `source.readFully(topMoov.size.toInt())` where
            // `topMoov.size` is a Long. A declared size >= Int.MAX_VALUE would,
            // if narrowed naively, produce a negative or truncated Int passed
            // to readFully. The MOOV_SOFT_LIMIT_BYTES (200 MB) guard runs
            // *before* the `.toInt()` narrowing — any size above 200 MB is
            // rejected as CorruptHeader, so the value reaching `.toInt()` is
            // always <= 200 MB and the narrowing is always safe. This test
            // pins that ordering: a 3 GB declared moov never overflows.
            val declaredMoovSize = 3L * 1024 * 1024 * 1024 // 3 GB > Int.MAX_VALUE
            val header = atomHeader("moov", size32 = declaredMoovSize)
            val source = MoovHeaderOnlySource(header = header, claimedLength = declaredMoovSize)

            val result = runBlocking { parser.parse(source) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
            // The soft-limit guard fired before `.toInt()` — no negative-size
            // readFully was ever attempted.
            (source.maxSingleReadBytes < 1024) shouldBe true
        }

        test("64-bit extended box size declaring a giant moov is rejected, not allocated") {
            // A top-level atom with size32 == 1 signals a 64-bit extended size
            // in the following 8 bytes. The extended size declares ~8 GB.
            // `findTopLevelAtom` reads the 8-byte size64 and checks
            // `offset + size64 > length` -> returns null. moov never found ->
            // CorruptHeader. No readFully of the 8 GB ever happens.
            val extended =
                atomHeader("moov", size32 = 1) + be64(8L * 1024 * 1024 * 1024)
            // File holds only the 16-byte extended header.
            val result = runBlocking { parser.parse(byteSource(extended)) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }

        test("box with a below-header-minimum declared size does not loop or throw") {
            // A top-level atom declaring size32 == 4 — below the 8-byte
            // minimum. `findTopLevelAtom` returns null on `size32 < 8L`
            // rather than advancing `offset` by 4 forever (infinite loop) or
            // a negative amount. moov never found -> CorruptHeader.
            val bytes = atomHeader("moov", size32 = 4)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }

        test("box with a declared size of zero does not loop forever") {
            // size32 == 0 means "extends to EOF" per ISO/IEC 14496-12. The
            // walker treats it as `length - offset`. A non-moov atom claiming
            // size 0 consumes the rest of the file in one step, the loop ends,
            // moov is never found -> CorruptHeader. The danger a missing guard
            // would create is `offset += 0` looping forever; this proves it
            // terminates.
            val bytes = atomHeader("ftyp", size32 = 0) + "M4B isom".toByteArray(Charsets.US_ASCII)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }

        test("truncated top-level atom header — fewer than 8 bytes — does not throw") {
            // Only 5 bytes: not even a full 8-byte atom header. The walker's
            // loop guard `offset + 8 <= length` is false from the start, so
            // it returns null immediately. moov never found -> CorruptHeader.
            val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20, 'f'.code.toByte())

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }

        test("64-bit extended box truncated before its 8-byte size field is read does not throw") {
            // size32 == 1 promises an 8-byte extended size, but the file ends
            // right after the 8-byte standard header — the extended size is
            // missing. `findTopLevelAtom` checks `offset + 16 > length` and
            // returns null. No read of the missing bytes is attempted.
            val bytes = atomHeader("moov", size32 = 1) // 8 bytes only, no size64

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }

        test("64-bit extended box with size64 below the 16-byte minimum is rejected") {
            // size32 == 1, extended size64 == 8 — below the 16-byte minimum
            // for a 64-bit-size box. `findTopLevelAtom` rejects on
            // `size64 < 16` -> returns null -> CorruptHeader. A missing guard
            // would advance `offset` by 8 and could loop or mis-parse.
            val bytes = atomHeader("moov", size32 = 1) + be64(8)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }

        // C-03: the chapter text-track sample-table counts (`stts`/`stsz`/`stco`/`co64`) are
        // untrusted 32-bit fields that directly drive IntArray/LongArray allocations and an
        // unbounded append loop in Mp4ChapterExtractor. Each test below is malicious in exactly
        // ONE of those fields (the rest are benign zero-count atoms) so a failure is attributable
        // to a single site.
        //
        // Every one asserts Success rather than "either outcome". That distinction is the whole
        // test: `Mp4Parser.parse` wraps this path in a catch-all that answers any escaped
        // Throwable — an OutOfMemoryError from an uncapped array included — with a typed
        // CorruptHeader. A case that also accepted Failure would therefore pass just as happily
        // with its cap deleted, which makes it no test of the cap at all. Success pins the real
        // property: the declared count was reconciled with the bytes present, so nothing was
        // ever allocated and nothing was ever thrown.

        test("chapter text-track stts sampleCount near Int.MAX_VALUE returns in bounded memory") {
            // parseSampleStartsMs's inner `for (j in 0 until sampleCount)` has no bound on the
            // number of `MutableList<Long>` appends — a single stts entry declaring sampleCount =
            // 0x7FFFFFFF (~2.1 billion) in a 16-byte atom drives ~2.1 billion boxed-Long appends.
            val bytes =
                buildChapterTrackMoov(
                    sttsPayload = maliciousSttsPayload(),
                    stszPayload = benignStszPayload(),
                    stcoPayload = benignStcoPayload(),
                )

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.chapters.shouldBeEmpty()
        }

        test("chapter text-track stsz count near Int.MAX_VALUE returns in bounded memory") {
            // parseSampleSizes allocates `IntArray(count)` (~8 GB for 0x7FFFFFFF) BEFORE reading a
            // single per-entry size — the atom itself is only 12 bytes long.
            val bytes =
                buildChapterTrackMoov(
                    sttsPayload = benignSttsPayload(),
                    stszPayload = maliciousStszPayload(),
                    stcoPayload = benignStcoPayload(),
                )

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.chapters.shouldBeEmpty()
        }

        test("chapter text-track stco count near Int.MAX_VALUE returns in bounded memory") {
            // parseChunkOffsets allocates `LongArray(count)` (~17 GB for 0x7FFFFFFF) BEFORE reading
            // a single per-entry offset — the atom itself is only 8 bytes long.
            val bytes =
                buildChapterTrackMoov(
                    sttsPayload = benignSttsPayload(),
                    stszPayload = benignStszPayload(),
                    stcoPayload = maliciousStcoPayload(),
                )

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.chapters.shouldBeEmpty()
        }

        test("chapter text-track co64 count near Int.MAX_VALUE returns in bounded memory") {
            // `parseChunkOffsets` serves BOTH chunk-offset box types from one cap, but the divisor
            // differs: a `co64` entry is 8 bytes wide where an `stco` entry is 4. That second arm
            // of the formula has no other coverage, so an entry count declared far beyond what the
            // box holds is fed to the 64-bit variant specifically.
            val bytes =
                buildChapterTrackMoov(
                    sttsPayload = benignSttsPayload(),
                    stszPayload = benignStszPayload(),
                    stcoPayload = maliciousStcoPayload(),
                    chunkOffsetType = "co64",
                )

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.chapters.shouldBeEmpty()
        }

        // C-04: Mp4Parser.parse's post-moov body (readMvhdDurationMs, ilst, chapters) runs
        // unguarded, and AtomWalker.readHeader's `offset + size > end` bounds check is
        // overflow-prone for an attacker-controlled `size` near Int.MAX_VALUE at a non-zero
        // offset. Both scenarios must surface AppResult.Failure(CorruptHeader) — never an
        // uncaught exception.

        test("mvhd atom with no payload at the end of moov does not throw indexing the version byte") {
            // mvhd is a bare 8-byte header (no payload) and is the LAST byte in the buffer, so
            // mvhd.dataOffset == bytes.size. readMvhdDurationMs's own bounds check catches this
            // before indexing `bytes[p]` and degrades gracefully to durationMs = 0 rather than
            // failing the whole parse — mvhd being truncated doesn't mean the rest of the file
            // (tags, chapters) isn't genuinely readable.
            val bytes = atom("moov", atomHeader("mvhd", size32 = 8))

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.durationMs shouldBe 0L
        }

        // The chapter-track resolver reads a one-byte version field at the head of `tkhd` and
        // `mdhd` to pick between the two field layouts each box version defines. A box that
        // carries no payload at all has no such byte. Placing that empty box last in the buffer
        // is what makes the difference observable: the read has nowhere to land. The correct
        // answer is to skip the unreadable box — the file's tags and duration are still perfectly
        // good — not to fail the file and lose them.

        test("a chapter track whose tkhd box carries no payload still yields the rest of the file") {
            val emptyTkhd = atomHeader("tkhd", size32 = 8)
            val mdhd = atom("mdhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000))
            val stbl =
                atom(
                    "stbl",
                    atom("stts", benignSttsPayload()) +
                        atom("stsz", benignStszPayload()) +
                        atom("stco", benignStcoPayload()),
                )
            val mdia = atom("mdia", mdhd + atom("minf", stbl))
            val bytes = moovAroundChapterTrak(mdia + emptyTkhd)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.durationMs shouldBe 90_000L
            success.data.chapters.shouldBeEmpty()
        }

        test("a chapter track whose mdhd box carries no payload still yields the rest of the file") {
            val tkhd = atom("tkhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(2))
            val emptyMdhd = atomHeader("mdhd", size32 = 8)
            val stbl =
                atom(
                    "stbl",
                    atom("stts", benignSttsPayload()) +
                        atom("stsz", benignStszPayload()) +
                        atom("stco", benignStcoPayload()),
                )
            val mdia = atom("mdia", atom("minf", stbl) + emptyMdhd)
            val bytes = moovAroundChapterTrak(tkhd + mdia)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.durationMs shouldBe 90_000L
            success.data.chapters.shouldBeEmpty()
        }

        test("a covr data box with no payload yields no artwork rather than failing the file") {
            // The artwork reader takes a 4-byte type prefix off the head of the `data` box before
            // it can classify the image bytes that follow. A payload-free box has neither. Same
            // last-in-buffer placement, same expectation: no artwork, everything else intact.
            val emptyData = atomHeader("data", size32 = 8)
            val ilst = atom("ilst", atom("covr", emptyData))
            val udta = atom("udta", atom("meta", ByteArray(4) + ilst))
            val mvhd = atom("mvhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000) + be32(90_000))
            val bytes = atom("moov", mvhd + udta)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.artwork shouldBe null
            success.data.durationMs shouldBe 90_000L
        }

        // A decoded duration is not just a number shown to a reader: it sizes the HLS segment
        // timeline, the seek bar, and the transcode plan. A movie header whose duration field
        // cannot be believed must report "unknown" (0), never a number the rest of the system
        // would then size work from.

        test("an mvhd v0 duration carrying the ISO unknown sentinel reports duration 0") {
            // Version 0 spells "duration not known" as an all-ones 32-bit field. Read literally
            // it decodes to about seven weeks of audio.
            val mvhd = atom("mvhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000) + be32(0xFFFFFFFFL))

            val result = runBlocking { parser.parse(byteSource(atom("moov", mvhd))) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.durationMs shouldBe 0L
        }

        test("an mvhd v1 duration that would overflow the millisecond conversion reports duration 0") {
            // Version 1 carries a signed 64-bit duration; scaling it to milliseconds multiplies
            // by 1000, which wraps for large values and yields a nonsense (often negative) result.
            val versionFlags = byteArrayOf(1, 0, 0, 0)
            val mvhd = atom("mvhd", versionFlags + ByteArray(8) + ByteArray(8) + be32(1000) + be64(Long.MAX_VALUE))

            val result = runBlocking { parser.parse(byteSource(atom("moov", mvhd))) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.durationMs shouldBe 0L
        }

        test("an mvhd duration past any believable audiobook length reports duration 0") {
            // Converts cleanly and is not the sentinel — it is simply about 1,100 hours, which no
            // audiobook is. This is the case the plausibility band exists for.
            val mvhd = atom("mvhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000) + be32(0xF0000000L))

            val result = runBlocking { parser.parse(byteSource(atom("moov", mvhd))) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.durationMs shouldBe 0L
        }

        test("an mvhd duration at the long end of the believable band is kept") {
            // 150 hours — longer than any book in a real library, and still honoured. The band
            // must reject nonsense without truncating the outliers that genuinely exist.
            val durationUnits = 150L * 60 * 60 * 1000
            val mvhd = atom("mvhd", ByteArray(4) + ByteArray(4) + ByteArray(4) + be32(1000) + be32(durationUnits))

            val result = runBlocking { parser.parse(byteSource(atom("moov", mvhd))) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.durationMs shouldBe durationUnits
        }

        test("atom size declared near Int.MAX_VALUE at a non-zero offset overflows offset+size safely") {
            // The lone child of moov starts at offset 8 (right after moov's own 8-byte header —
            // a non-zero offset) and declares a size that, added to that offset, overflows Int:
            // the pre-fix `offset + size > end` check silently passes because the sum wraps
            // negative, producing an Atom whose `.end` is also negative. The walker's next
            // `readHeader` call then indexes `bytes[<negative offset>]`.
            val bytes = atom("moov", atomHeader("free", size32 = Int.MAX_VALUE.toLong() - 5))

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            // mvhd is never found behind the lying "free" atom -> CorruptHeader, never an
            // uncaught exception.
            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
        }
    })

/**
 * Synthetic [SeekableSource] that reports a large [claimedLength] but
 * only physically holds the [header] bytes. A `readFully` confined to the
 * header is served verbatim; any read straying past it throws [IOException]
 * — it would have to materialise zero-fill the parser never legitimately
 * needs, so an oversized `moov` read fails loudly instead of silently
 * allocating a giant ByteArray. [maxSingleReadBytes] records the largest
 * single `readFully` so a test can prove no oversized allocation was tried.
 */
private class MoovHeaderOnlySource(
    private val header: ByteArray,
    private val claimedLength: Long,
) : SeekableSource {
    var maxSingleReadBytes: Int = 0
        private set

    private var pos: Long = 0

    override val length: Long get() = claimedLength

    override fun position(): Long = pos

    override fun seek(offset: Long) {
        require(offset in 0..claimedLength) { "seek out of range: $offset" }
        pos = offset
    }

    override fun read(
        into: ByteArray,
        count: Int,
    ): Int {
        if (pos >= claimedLength) return -1
        maxSingleReadBytes = maxOf(maxSingleReadBytes, count)
        val n = (claimedLength - pos).coerceAtMost(count.toLong()).toInt()
        if (pos < header.size) {
            val fromHeader = minOf(n, header.size - pos.toInt())
            System.arraycopy(header, pos.toInt(), into, 0, fromHeader)
        }
        pos += n
        return n
    }

    override fun readFully(count: Int): ByteArray {
        maxSingleReadBytes = maxOf(maxSingleReadBytes, count)
        if (pos + count > claimedLength) {
            throw IOException("EOF: pos=$pos count=$count length=$claimedLength")
        }
        // A read past the header means the parser ignored the soft-limit guard
        // and is trying to pull the (non-existent) oversized moov body — fail
        // loudly rather than silently zero-filling a giant allocation.
        if (pos + count > header.size) {
            throw IOException("read past header — guard gap: pos=$pos count=$count headerSize=${header.size}")
        }
        val buf = ByteArray(count)
        System.arraycopy(header, pos.toInt(), buf, 0, count)
        pos += count
        return buf
    }

    override fun close() { /* no-op */ }
}
