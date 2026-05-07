package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.AudioFormatParser
import com.calypsan.listenup.server.embeddedmeta.SeekableAudioSource
import java.io.IOException

/**
 * Parses ID3v2 / ID3v1 tags + MPEG audio frame technical info from MP3 files.
 *
 * Behaviour summary:
 * - **Tags.** Prefers ID3v2 (version 2.3 and 2.4); falls back to the 128-byte
 *   ID3v1 footer when ID3v2 is absent. Text frames map to canonical [AudioTags]
 *   slots; unmapped `T*` frames and all `TXXX` user-defined frames land in
 *   [AudioTags.custom]. UTF-16 text frames are decoded via the shared
 *   `BinaryDecoder.readUtf16WithBom` helper.
 * - **Chapters.** ID3v2 `CHAP` frames produce [Chapter] entries with
 *   [ChapterSource.Id3v2Chap]. Each `CHAP` may embed a `TIT2` sub-frame
 *   carrying the chapter title; if absent the element id is used.
 * - **Artwork.** `APIC` frames are scanned in tag order; the highest-priority
 *   match wins (picture type 3 "Cover (front)" beats every other type;
 *   otherwise first APIC wins).
 * - **Duration.** CBR-only — read bitrate and sample rate from the first
 *   MPEG frame header, count frames at the nominal frame size, multiply by
 *   1152 samples/frame.
 *
 * Failure mapping:
 * - File-level read errors → [AudioMetadataError.IoError].
 * - ID3v2 sync-safe size overruns the file length → [AudioMetadataError.TruncatedStream].
 * - First MPEG sync byte cannot be located within the audio region →
 *   [AudioMetadataError.CorruptHeader] (synthesised so the duration field is
 *   zero rather than misleading).
 *
 * TODO(VBR): Xing/VBRI VBR-header parsing is deferred. Real-world audiobook
 * MP3s are overwhelmingly CBR; the duration approximation is good enough for
 * the scan summary. When a VBR file surfaces, port the `parseVBRHeader` block
 * from `/home/simonh/Code/audiometa/internal/mp3/technical.go`.
 */
internal class Mp3Parser : AudioFormatParser {
    override val supports: Set<AudioFormat> = setOf(AudioFormat.Mp3)

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

        val id3v2 = if (Id3v2Reader.hasId3v2Prefix(bytes)) Id3v2Reader.read(bytes) else null
        val id3v2Tags = id3v2?.tags
        val id3v2Chapters = id3v2?.chapters.orEmpty()
        val id3v2Artwork = id3v2?.artwork

        val tags =
            id3v2Tags ?: run {
                // Fall back to ID3v1 footer
                Id3v1Reader.read(bytes) ?: emptyTags()
            }

        val tagSize = id3v2?.tagSize ?: 0
        val durationMs = MpegDurationCalculator.compute(bytes, audioStart = tagSize.toLong())

        val chaptersSource =
            if (id3v2Chapters.isNotEmpty()) ChapterSource.Id3v2Chap else ChapterSource.None

        return AppResult.Success(
            EmbeddedAudioMetadata(
                format = AudioFormat.Mp3,
                durationMs = durationMs,
                tags = tags,
                chapters = id3v2Chapters,
                chaptersSource = chaptersSource,
                artwork = id3v2Artwork,
            ),
        )
    }

    private fun emptyTags(): AudioTags =
        AudioTags(
            title = null,
            subtitle = null,
            authors = emptyList(),
            narrators = emptyList(),
            series = emptyList(),
            genres = emptyList(),
            description = null,
            publisher = null,
            publishedYear = null,
            asin = null,
            isbn = null,
            language = null,
            trackNumber = null,
            discNumber = null,
            custom = emptyMap(),
        )
}
