@file:Suppress("MagicNumber") // Binary format constants — readability beats names.

package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import com.calypsan.listenup.domain.embeddedmeta.SeriesEntry

/**
 * Reads ID3v2.3 / ID3v2.4 tags out of an in-memory MP3 byte slice.
 *
 * Returns a structured [Id3v2ReadResult] containing the decoded [AudioTags],
 * any `CHAP` chapters, the highest-priority `APIC` artwork, and the total
 * tag size (header + body) so the caller can locate the audio region.
 *
 * Returns `null` if the buffer doesn't start with the "ID3" magic. Malformed
 * frames are skipped in line with the Go reference's tolerant behaviour
 * (`/home/simonh/Code/audiometa/internal/mp3/id3v2.go`).
 */
internal data class Id3v2ReadResult(
    val tags: AudioTags,
    val chapters: List<Chapter>,
    val artwork: EmbeddedArtwork?,
    val tagSize: Int,
)

internal object Id3v2Reader {
    fun hasId3v2Prefix(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0x49.toByte() &&
            bytes[1] == 0x44.toByte() &&
            bytes[2] == 0x33.toByte()

    /**
     * Decode the total tag size (10-byte header + body) from the first 10
     * bytes of the file. Returns `null` when the prefix isn't ID3v2,
     * the major version isn't 2.3/2.4, or the sync-safe size is malformed.
     *
     * Lets the caller size a single bounded read for the tag region instead
     * of loading the whole file just to discover how large the tag is.
     */
    fun peekTagSize(header: ByteArray): Int? {
        if (header.size < ID3V2_HEADER_SIZE) return null
        if (!hasId3v2Prefix(header)) return null
        val version = header[3].toInt() and 0xFF
        if (version != 3 && version != 4) return null
        val bodySize = decodeSyncSafe(header[6], header[7], header[8], header[9])
        if (bodySize < 0) return null
        return ID3V2_HEADER_SIZE + bodySize
    }

    fun read(bytes: ByteArray): Id3v2ReadResult? {
        if (!hasId3v2Prefix(bytes) || bytes.size < ID3V2_HEADER_SIZE) return null
        val version = bytes[3].toInt() and 0xFF
        if (version != 3 && version != 4) return null
        val flags = bytes[5].toInt() and 0xFF
        val bodySize = decodeSyncSafe(bytes[6], bytes[7], bytes[8], bytes[9])
        val totalSize = ID3V2_HEADER_SIZE + bodySize
        if (totalSize > bytes.size) {
            // Truncated tag — bail to caller's fallback.
            return null
        }

        var offset = ID3V2_HEADER_SIZE
        // Skip extended header if present (flags bit 0x40)
        if ((flags and 0x40) != 0 && offset + 4 <= bytes.size) {
            val extSize =
                if (version == 4) {
                    decodeSyncSafe(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3])
                } else {
                    decodeBigEndian32(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3])
                }
            offset += if (version == 4) extSize else extSize + 4
        }

        val builder = AudioTagsBuilder()
        val chapters = mutableListOf<Chapter>()
        var bestArtwork: EmbeddedArtwork? = null
        var bestArtworkPriority = -1

        val tagEnd = totalSize
        while (offset + ID3V2_FRAME_HEADER_SIZE <= tagEnd) {
            // Padding indicator — first frame-id byte is 0
            if (bytes[offset] == 0.toByte()) break
            val frameId = String(bytes, offset, 4, Charsets.ISO_8859_1)
            val frameSize =
                if (version == 4) {
                    decodeSyncSafe(bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7])
                } else {
                    decodeBigEndian32(bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7])
                }
            // Skip flags at offset+8, offset+9
            val frameDataStart = offset + ID3V2_FRAME_HEADER_SIZE
            val frameDataEnd = frameDataStart + frameSize
            if (frameSize < 0 || frameDataEnd > tagEnd) break
            val frameData = bytes.copyOfRange(frameDataStart, frameDataEnd)
            when {
                frameId == "TXXX" -> {
                    handleTxxx(frameData, builder)
                }

                frameId.startsWith("T") -> {
                    handleTextFrame(frameId, frameData, builder)
                }

                frameId == "CHAP" -> {
                    val chapter = parseChap(frameData, version, chapters.size + 1)
                    if (chapter != null) chapters += chapter
                }

                frameId == "APIC" -> {
                    val (artwork, priority) = parseApic(frameData) ?: (null to -1)
                    if (artwork != null && priority > bestArtworkPriority) {
                        bestArtwork = artwork
                        bestArtworkPriority = priority
                    }
                }
            }
            offset = frameDataEnd
        }

        val sortedChapters =
            chapters.sortedBy { it.startMs }.mapIndexed { i, c -> c.copy(index = i + 1) }

        return Id3v2ReadResult(
            tags = builder.build(),
            chapters = sortedChapters,
            artwork = bestArtwork,
            tagSize = totalSize,
        )
    }

    private fun handleTextFrame(
        frameId: String,
        data: ByteArray,
        builder: AudioTagsBuilder,
    ) {
        if (data.isEmpty()) return
        val encoding = data[0]
        val textBytes = data.copyOfRange(1, data.size)
        val text = decodeText(textBytes, encoding).trimNulls()
        if (text.isEmpty()) return
        when (frameId) {
            "TIT2" -> builder.title = text

            "TIT3" -> builder.subtitle = text

            "TPE1" -> builder.authors += text

            "TALB" -> builder.custom["album"] = text

            "TCON" -> builder.genres += text

            "TYER" -> parseYear(text)?.let { builder.publishedYear = it }

            "TDRC" -> parseYear(text)?.let { builder.publishedYear = it }

            "TCOM" -> builder.narrators += text

            // Composer often used as narrator in audiobooks
            "TRCK" -> parseTrack(text)?.let { builder.trackNumber = it }

            "TPOS" -> parseTrack(text)?.let { builder.discNumber = it }

            "TPUB" -> builder.publisher = text

            "TLAN" -> builder.language = text

            "MVNM" -> builder.seriesName = builder.seriesName ?: text

            "MVIN" -> builder.seriesPart = builder.seriesPart ?: text

            else -> builder.custom[frameId] = text
        }
    }

    private fun handleTxxx(
        data: ByteArray,
        builder: AudioTagsBuilder,
    ) {
        if (data.size < 2) return
        val encoding = data[0]
        val (description, value) = splitNullTerminated(data.copyOfRange(1, data.size), encoding) ?: return
        when (description.lowercase()) {
            "narrator" -> {
                builder.narrators += value
            }

            "series" -> {
                builder.seriesName = value
            }

            "series part", "seriespart", "part", "series-part", "series position" -> {
                builder.seriesPart = value
            }

            "publisher" -> {
                builder.publisher = value
            }

            "isbn" -> {
                builder.isbn = value
            }

            "asin", "audible_asin" -> {
                builder.asin = value
            }

            "language", "lang" -> {
                builder.language = value
            }

            "description" -> {
                builder.description = builder.description ?: value
            }

            else -> {
                builder.custom[description] = value
            }
        }
    }

    private fun parseChap(
        data: ByteArray,
        version: Int,
        provisionalIndex: Int,
    ): Chapter? {
        // Format: elementId\0 + startMs(4 BE) + endMs(4 BE) + startOffset(4) + endOffset(4) + subframes…
        val nullIdx = data.indexOf(0)
        if (nullIdx < 0) return null
        val elementId = String(data, 0, nullIdx, Charsets.ISO_8859_1)
        val rest = data.copyOfRange(nullIdx + 1, data.size)
        if (rest.size < 16) return null
        val startMs = readBigEndian32(rest, 0).toLong() and 0xFFFFFFFFL
        val endMs = readBigEndian32(rest, 4).toLong() and 0xFFFFFFFFL
        val subframes = rest.copyOfRange(16, rest.size)
        val title = extractChapterTitle(subframes, version) ?: elementId
        return Chapter(
            index = provisionalIndex,
            title = title,
            startMs = startMs,
            endMs = endMs,
        )
    }

    private fun extractChapterTitle(
        subframes: ByteArray,
        version: Int,
    ): String? {
        if (subframes.size < ID3V2_FRAME_HEADER_SIZE) return null
        val subId = String(subframes, 0, 4, Charsets.ISO_8859_1)
        if (subId != "TIT2") return null
        val subSize =
            if (version == 4) {
                decodeSyncSafe(subframes[4], subframes[5], subframes[6], subframes[7])
            } else {
                decodeBigEndian32(subframes[4], subframes[5], subframes[6], subframes[7])
            }
        val dataStart = ID3V2_FRAME_HEADER_SIZE
        val dataEnd = dataStart + subSize
        if (subSize <= 0 || dataEnd > subframes.size) return null
        val frameData = subframes.copyOfRange(dataStart, dataEnd)
        if (frameData.isEmpty()) return null
        val encoding = frameData[0]
        val textBytes = frameData.copyOfRange(1, frameData.size)
        val text = decodeText(textBytes, encoding).trimNulls()
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns `(artwork, priority)` — higher priority wins. Picture type 3
     * ("Cover (front)") beats every other type; first APIC seen has priority 0.
     */
    private fun parseApic(data: ByteArray): Pair<EmbeddedArtwork, Int>? {
        if (data.size < 4) return null
        val encoding = data[0]
        var pos = 1
        // MIME type — always ISO-8859-1, single-byte null
        val mimeEnd = (pos until data.size).firstOrNull { data[it] == 0.toByte() } ?: return null
        var mime = String(data, pos, mimeEnd - pos, Charsets.ISO_8859_1)
        pos = mimeEnd + 1
        if (pos >= data.size) return null
        val pictureType = data[pos].toInt() and 0xFF
        pos++
        // Description — encoding-dependent terminator
        val descTerm = findEncodingTerminator(data, pos, encoding)
        if (descTerm < 0) return null
        pos = descTerm + terminatorSize(encoding)
        if (pos > data.size) return null
        val imageBytes = data.copyOfRange(pos, data.size)
        if (imageBytes.isEmpty()) return null
        // Sniff MIME from magic bytes; tolerate encoder-supplied "image/png"
        val sniffed = sniffMime(imageBytes)
        if (sniffed != null) mime = sniffed
        val priority = if (pictureType == APIC_FRONT_COVER) 100 else 1
        return EmbeddedArtwork(mime = mime, bytes = imageBytes) to priority
    }

    private fun sniffMime(bytes: ByteArray): String? =
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"

            bytes.size >= 4 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "image/png"

            else -> null
        }

    private fun decodeText(
        data: ByteArray,
        encoding: Byte,
    ): String =
        when (encoding) {
            0.toByte() -> String(data, Charsets.ISO_8859_1)
            1.toByte() -> decodeUtf16WithBom(data)
            2.toByte() -> String(data, Charsets.UTF_16BE)
            3.toByte() -> String(data, Charsets.UTF_8)
            else -> String(data, Charsets.ISO_8859_1)
        }

    private fun decodeUtf16WithBom(data: ByteArray): String {
        if (data.size < 2) return ""
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        return when {
            b0 == 0xFE && b1 == 0xFF -> String(data, 2, data.size - 2, Charsets.UTF_16BE)
            b0 == 0xFF && b1 == 0xFE -> String(data, 2, data.size - 2, Charsets.UTF_16LE)
            else -> String(data, Charsets.UTF_16BE)
        }
    }

    private fun splitNullTerminated(
        data: ByteArray,
        encoding: Byte,
    ): Pair<String, String>? {
        val termIdx = findEncodingTerminator(data, 0, encoding)
        if (termIdx < 0) return null
        val description = decodeText(data.copyOfRange(0, termIdx), encoding)
        val valueStart = termIdx + terminatorSize(encoding)
        if (valueStart > data.size) return null
        val value = decodeText(data.copyOfRange(valueStart, data.size), encoding)
        return description to value
    }

    private fun findEncodingTerminator(
        data: ByteArray,
        from: Int,
        encoding: Byte,
    ): Int {
        return when (encoding) {
            1.toByte(), 2.toByte() -> {
                // Double-byte null
                var i = from
                while (i < data.size - 1) {
                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
                    i += 2
                }
                -1
            }

            else -> {
                // Single-byte null
                (from until data.size).firstOrNull { data[it] == 0.toByte() } ?: -1
            }
        }
    }

    private fun terminatorSize(encoding: Byte): Int =
        when (encoding) {
            1.toByte(), 2.toByte() -> 2
            else -> 1
        }

    private fun parseYear(text: String): Int? {
        if (text.length < 4) return null
        val candidate = text.substring(0, 4).toIntOrNull() ?: return null
        return if (candidate in 1900..2100) candidate else null
    }

    private fun parseTrack(text: String): Int? = text.substringBefore('/').toIntOrNull()

    private fun decodeSyncSafe(
        b0: Byte,
        b1: Byte,
        b2: Byte,
        b3: Byte,
    ): Int =
        ((b0.toInt() and 0x7F) shl 21) or
            ((b1.toInt() and 0x7F) shl 14) or
            ((b2.toInt() and 0x7F) shl 7) or
            (b3.toInt() and 0x7F)

    private fun decodeBigEndian32(
        b0: Byte,
        b1: Byte,
        b2: Byte,
        b3: Byte,
    ): Int =
        ((b0.toInt() and 0xFF) shl 24) or
            ((b1.toInt() and 0xFF) shl 16) or
            ((b2.toInt() and 0xFF) shl 8) or
            (b3.toInt() and 0xFF)

    private fun readBigEndian32(
        data: ByteArray,
        offset: Int,
    ): Int = decodeBigEndian32(data[offset], data[offset + 1], data[offset + 2], data[offset + 3])

    /**
     * Strip leading/trailing null characters (encoder-emitted terminators).
     *
     * Does not strip whitespace -- \r/\n/\t may legitimately appear inside an authored
     * title/description field and must round-trip.
     */
    private fun String.trimNulls(): String = this.trim { it == '\u0000' }

    private const val ID3V2_HEADER_SIZE = 10
    private const val ID3V2_FRAME_HEADER_SIZE = 10
    private const val APIC_FRONT_COVER = 3
}

internal class AudioTagsBuilder {
    var title: String? = null
    var subtitle: String? = null
    val authors: MutableList<String> = mutableListOf()
    val narrators: MutableList<String> = mutableListOf()
    var seriesName: String? = null
    var seriesPart: String? = null
    val genres: MutableList<String> = mutableListOf()
    var description: String? = null
    var publisher: String? = null
    var publishedYear: Int? = null
    var asin: String? = null
    var isbn: String? = null
    var language: String? = null
    var trackNumber: Int? = null
    var discNumber: Int? = null
    val custom: MutableMap<String, String> = linkedMapOf()

    fun build(): AudioTags {
        val series =
            if (seriesName != null) {
                listOf(SeriesEntry(name = seriesName!!, sequence = seriesPart))
            } else {
                emptyList()
            }
        return AudioTags(
            title = title,
            subtitle = subtitle,
            authors = authors.toList(),
            narrators = narrators.toList(),
            series = series,
            genres = genres.toList(),
            description = description,
            publisher = publisher,
            publishedYear = publishedYear,
            asin = asin,
            isbn = isbn,
            language = language,
            trackNumber = trackNumber,
            discNumber = discNumber,
            custom = custom.toMap(),
        )
    }
}
