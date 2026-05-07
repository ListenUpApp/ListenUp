@file:Suppress("MagicNumber") // Atom-format constants — readability beats names.

package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.AudioFormatParser
import com.calypsan.listenup.server.embeddedmeta.SeekableAudioSource
import java.io.IOException

/**
 * Parses MP4 / M4A / M4B audiobook metadata.
 *
 * The MP4 container is an atom (box) tree:
 * - `ftyp` declares the major brand (`M4A `, `M4B `, …).
 * - `moov.mvhd` carries the movie timescale and total duration; we derive
 *   `durationMs = duration * 1000 / timescale`. Both v0 (32-bit duration)
 *   and v1 (64-bit duration) headers are supported.
 * - `moov.udta.meta.ilst` carries iTunes-style tags (`©nam`, `©ART`,
 *   `covr`, `----`). [IlstReader] decodes them; reverse-DNS `----` atoms
 *   carry audiobook fields (Narrator, ASIN, Series, …).
 * - Chapters: spec §8.4 prefers Nero `chpl` over Apple QuickTime
 *   text-track when both are present. [Mp4ChapterExtractor] orchestrates
 *   the precedence; the parser surfaces the chosen source via
 *   [EmbeddedAudioMetadata.chaptersSource].
 *
 * Failure mapping:
 * - File-level read errors → [AudioMetadataError.IoError].
 * - Atom-walker rejects (size < 8, atom extends past EOF) →
 *   [AudioMetadataError.CorruptHeader].
 * - Required atom missing (`moov` or `mvhd`) →
 *   [AudioMetadataError.CorruptHeader] — the file is structurally invalid
 *   per ISO/IEC 14496-12; best surfaced rather than guessed at.
 *
 * Reference: spec §8.4 + Go `/home/simonh/Code/audiometa/internal/m4a/`.
 */
internal class Mp4Parser : AudioFormatParser {
    override val supports: Set<AudioFormat> = setOf(AudioFormat.Mp4)

    override suspend fun parse(source: SeekableAudioSource): AppResult<EmbeddedAudioMetadata> {
        val bytes =
            try {
                source.seek(0)
                source.readFully(source.length.toInt())
            } catch (e: IOException) {
                return AppResult.Failure(
                    AudioMetadataError.IoError(
                        pathString = "<source>",
                        ioMessage = e.message ?: "io error",
                    ),
                )
            }

        val moov =
            try {
                AtomWalker.findPath(bytes, "moov")
            } catch (e: AtomParseException) {
                return AppResult.Failure(
                    AudioMetadataError.CorruptHeader(
                        pathString = "<source>",
                        format = AudioFormat.Mp4,
                        offset = e.offset.toLong(),
                        expected = e.expected,
                    ),
                )
            } ?: return AppResult.Failure(
                AudioMetadataError.CorruptHeader(
                    pathString = "<source>",
                    format = AudioFormat.Mp4,
                    offset = 0,
                    expected = "moov atom",
                ),
            )

        val mvhd =
            AtomWalker.findChild(bytes, moov.dataOffset, moov.end, "mvhd")
                ?: return AppResult.Failure(
                    AudioMetadataError.CorruptHeader(
                        pathString = "<source>",
                        format = AudioFormat.Mp4,
                        offset = moov.offset.toLong(),
                        expected = "moov.mvhd atom",
                    ),
                )

        val durationMs = readMvhdDurationMs(bytes, mvhd)

        val ilst = findIlst(bytes, moov)
        val ilstResult = ilst?.let { IlstReader.read(bytes, it) }
        val tags = ilstResult?.tags ?: emptyAudioTags()
        val artwork = ilstResult?.artwork

        val chapterResult = extractMp4Chapters(bytes, moov, durationMs)

        return AppResult.Success(
            EmbeddedAudioMetadata(
                format = AudioFormat.Mp4,
                durationMs = durationMs,
                tags = tags,
                chapters = chapterResult.chapters,
                chaptersSource = if (chapterResult.chapters.isEmpty()) ChapterSource.None else chapterResult.source,
                artwork = artwork,
            ),
        )
    }

    /** Locate `moov.udta.meta.ilst`, accounting for `meta`'s 4-byte version+flags prefix. */
    private fun findIlst(
        bytes: ByteArray,
        moov: Atom,
    ): Atom? {
        val udta = AtomWalker.findChild(bytes, moov.dataOffset, moov.end, "udta") ?: return null
        val meta = AtomWalker.findChild(bytes, udta.dataOffset, udta.end, "meta") ?: return null
        // meta has a 4-byte version+flags prefix before its child atoms.
        val metaChildStart = meta.dataOffset + 4
        return AtomWalker.findChild(bytes, metaChildStart, meta.end, "ilst")
    }

    /**
     * Decode the millisecond duration from a `mvhd` atom. Version 0 carries
     * 32-bit duration; version 1 carries 64-bit. Returns 0 if the timescale
     * is invalid (zero / negative).
     */
    private fun readMvhdDurationMs(
        bytes: ByteArray,
        mvhd: Atom,
    ): Long {
        var p = mvhd.dataOffset
        val version = bytes[p].toInt() and 0xFF
        p += 1
        p += 3 // flags
        val (timescale, durationUnits) =
            if (version == 1) {
                p += 16 // creation(8) + modification(8)
                val ts = AtomWalker.readBeInt32(bytes, p)
                p += 4
                val d = AtomWalker.readBeInt64(bytes, p)
                ts to d
            } else {
                p += 8 // creation(4) + modification(4)
                val ts = AtomWalker.readBeInt32(bytes, p)
                p += 4
                val d = AtomWalker.readBeUInt32(bytes, p)
                ts to d
            }
        if (timescale <= 0) return 0
        return (durationUnits * 1000L) / timescale.toLong()
    }
}
