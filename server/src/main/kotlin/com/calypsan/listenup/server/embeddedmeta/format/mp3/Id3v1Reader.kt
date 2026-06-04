
package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.domain.embeddedmeta.AudioTags

/**
 * Reads the legacy 128-byte ID3v1 footer when no ID3v2 tag is present.
 *
 * Layout: `"TAG"` + 30b title + 30b artist + 30b album + 4b year + 30b comment + 1b genre.
 * All text fields are ISO-8859-1 padded with `0x00` or `0x20` (space).
 *
 * Returns `null` if the file is shorter than 128 bytes or the trailing block
 * doesn't start with "TAG". Returns a sparsely populated [AudioTags] otherwise
 * — fields the v1 footer doesn't carry are left null/empty.
 */
internal object Id3v1Reader {
    fun read(bytes: ByteArray): AudioTags? {
        if (bytes.size < ID3V1_LEN) return null
        val start = bytes.size - ID3V1_LEN
        if (!hasTagMagic(bytes, start)) return null

        val title = readField(bytes, start + 3, 30)
        val artist = readField(bytes, start + 33, 30)
        val album = readField(bytes, start + 63, 30)
        val year = readField(bytes, start + 93, 4).toIntOrNull()?.takeIf { it in 1900..2100 }
        val comment = readField(bytes, start + 97, 30)
        // Genre byte at start + 127 — ID3v1 genre table not implemented (unused by audiobooks).

        return AudioTags(
            title = title.takeIf { it.isNotEmpty() },
            subtitle = null,
            authors = if (artist.isNotEmpty()) listOf(artist) else emptyList(),
            narrators = emptyList(),
            series = emptyList(),
            genres = emptyList(),
            description = comment.takeIf { it.isNotEmpty() },
            publisher = null,
            publishedYear = year,
            asin = null,
            isbn = null,
            language = null,
            trackNumber = null,
            discNumber = null,
            custom = if (album.isNotEmpty()) mapOf("album" to album) else emptyMap(),
        )
    }

    private fun hasTagMagic(
        bytes: ByteArray,
        start: Int,
    ): Boolean =
        bytes[start] == 0x54.toByte() &&
            bytes[start + 1] == 0x41.toByte() &&
            bytes[start + 2] == 0x47.toByte()

    private fun readField(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        // Strip trailing null/space padding
        var end = offset + length
        while (end > offset && (bytes[end - 1] == 0.toByte() || bytes[end - 1] == 0x20.toByte())) {
            end--
        }
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
    }

    private const val ID3V1_LEN = 128
}
