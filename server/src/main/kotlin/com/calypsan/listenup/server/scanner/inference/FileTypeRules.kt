package com.calypsan.listenup.server.scanner.inference

import com.calypsan.listenup.api.dto.scanner.FileType

/**
 * Maps a filename to its [FileType] using the same extension sets as ABS
 * (`audiobookshelf/server/utils/globals.js`). Classification happens once
 * at the Walker; downstream stages filter by [FileType] without re-parsing
 * the filename.
 *
 * Extensions are matched case-insensitively. Files with no extension or an
 * unrecognised extension are [FileType.UNKNOWN] — not skipped, just
 * unclassified, so the Analyzer can see them if it cares.
 */
object FileTypeRules {
    private val audioExt =
        setOf(
            "m4b",
            "mp3",
            "m4a",
            "flac",
            "opus",
            "ogg",
            "oga",
            "mp4",
            "aac",
            "wma",
            "aiff",
            "aif",
            "wav",
            "webm",
            "webma",
            "mka",
            "awb",
            "caf",
            "mpg",
            "mpeg",
        )
    private val imageExt = setOf("png", "jpg", "jpeg", "webp")
    private val ebookExt = setOf("epub", "pdf", "mobi", "azw3", "cbr", "cbz")
    private val textExt = setOf("txt", "nfo")
    private val metadataExt = setOf("opf", "abs", "xml", "json")

    fun classify(filename: String): FileType {
        val ext = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isEmpty()) return FileType.UNKNOWN
        return when (ext) {
            in audioExt -> FileType.AUDIO
            in imageExt -> FileType.IMAGE
            in ebookExt -> FileType.EBOOK
            in textExt -> FileType.TEXT
            in metadataExt -> FileType.METADATA
            else -> FileType.UNKNOWN
        }
    }
}
