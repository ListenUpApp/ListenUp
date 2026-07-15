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
internal object FileTypeRules {
    // Mirrors ABS's SupportedAudioTypes with two deliberate divergences: `mpg`/`mpeg` are dropped
    // (they are MPEG *video* containers ABS mis-lists as audio — admitting them turns a stray video
    // clip into a failing "book"), and `m4p` is added (Apple's DRM-protected AAC — a real audio
    // container, classified as AUDIO so it forms a book candidate rather than being silently dropped;
    // the embedded parser surfaces its own status if the DRM blocks a clean read).
    private val audioExt =
        setOf(
            "m4b",
            "mp3",
            "m4a",
            "m4p",
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
