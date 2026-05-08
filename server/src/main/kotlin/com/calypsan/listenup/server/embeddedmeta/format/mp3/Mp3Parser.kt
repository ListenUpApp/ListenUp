package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
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
 * - **Chapters.** ID3v2 `CHAP` frames produce [com.calypsan.listenup.domain.embeddedmeta.Chapter]
 *   entries with [ChapterSource.Id3v2Chap]. Each `CHAP` may embed a `TIT2`
 *   sub-frame carrying the chapter title; if absent the element id is used.
 * - **Artwork.** `APIC` frames are scanned in tag order; the highest-priority
 *   match wins (picture type 3 "Cover (front)" beats every other type;
 *   otherwise first APIC wins).
 * - **Duration.** CBR-only — read bitrate and sample rate from the first
 *   MPEG frame header found within a 64 KB sniff window past the tag,
 *   then derive duration from the audio-region size.
 *
 * Streaming contract: the parser only reads bounded regions out of [source]
 * — the ID3v2 tag (capped at [ID3V2_SOFT_LIMIT_BYTES]), the trailing 128-byte
 * ID3v1 footer, and a 64 KB sniff window for the first MPEG frame. Files
 * larger than the heap parse without ever materialising the audio body.
 *
 * Failure mapping:
 * - File-level read errors → [AudioMetadataError.IoError].
 *
 * TODO(VBR): Xing/VBRI VBR-header parsing is deferred. Real-world audiobook
 * MP3s are overwhelmingly CBR; the duration approximation is good enough for
 * the scan summary. When a VBR file surfaces, port the `parseVBRHeader` block
 * from `/home/simonh/Code/audiometa/internal/mp3/technical.go`.
 */
internal class Mp3Parser : AudioFormatParser {
    override val supports: Set<AudioFormat> = setOf(AudioFormat.Mp3)

    override suspend fun parse(source: SeekableAudioSource): AppResult<EmbeddedAudioMetadata> =
        try {
            val id3v2 = readId3v2Region(source)
            val id3v1Tags = readId3v1Footer(source)
            val hasV1Footer = id3v1Tags != null

            val tags = id3v2?.tags ?: id3v1Tags ?: emptyTags()
            val tagSize = id3v2?.tagSize ?: 0
            val chapters = id3v2?.chapters.orEmpty()

            val durationMs =
                MpegDurationCalculator.compute(
                    source = source,
                    audioStart = tagSize.toLong(),
                    hasV1Footer = hasV1Footer,
                )

            AppResult.Success(
                EmbeddedAudioMetadata(
                    format = AudioFormat.Mp3,
                    durationMs = durationMs,
                    tags = tags,
                    chapters = chapters,
                    chaptersSource = if (chapters.isNotEmpty()) ChapterSource.Id3v2Chap else ChapterSource.None,
                    artwork = id3v2?.artwork,
                ),
            )
        } catch (e: IOException) {
            AppResult.Failure(
                AudioMetadataError.IoError(
                    pathString = "<source>",
                    ioMessage = e.message ?: "io error",
                ),
            )
        }

    private fun readId3v2Region(source: SeekableAudioSource): Id3v2ReadResult? {
        if (source.length < ID3V2_HEADER_SIZE) return null
        source.seek(0)
        val header = source.readFully(ID3V2_HEADER_SIZE)
        val tagSize = Id3v2Reader.peekTagSize(header) ?: return null
        if (tagSize > ID3V2_SOFT_LIMIT_BYTES) return null
        if (tagSize > source.length) return null
        source.seek(0)
        val tagBytes = source.readFully(tagSize)
        return Id3v2Reader.read(tagBytes)
    }

    private fun readId3v1Footer(source: SeekableAudioSource): AudioTags? {
        if (source.length < ID3V1_LEN) return null
        source.seek(source.length - ID3V1_LEN)
        val footer = source.readFully(ID3V1_LEN)
        return Id3v1Reader.read(footer)
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

    private companion object {
        /**
         * Defensive cap on ID3v2 tag size. Real-world ID3v2 tags max out around
         * 1-10 MB even with full-resolution embedded cover art. A 200 MB cap
         * leaves generous headroom while preventing a corrupt sync-safe size
         * field from triggering a multi-GB allocation. Mirrors `Mp4Parser`'s
         * `MOOV_SOFT_LIMIT_BYTES`.
         */
        private const val ID3V2_SOFT_LIMIT_BYTES = 200 * 1024 * 1024
        private const val ID3V2_HEADER_SIZE = 10
        private const val ID3V1_LEN = 128
    }
}
