package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.sync.CoverSource

/**
 * Cover bytes extracted from an [com.calypsan.listenup.api.dto.scanner.AnalyzedBook] before
 * the DB upsert, ready to be stored into the managed cover store once the stable
 * [com.calypsan.listenup.core.BookId] is known.
 *
 * [bytes] are the raw image bytes; [mime] is the MIME type declared by the
 * source (e.g. `"image/jpeg"`); [source] is the sync-layer provenance tag
 * ([CoverSource.FILESYSTEM] or [CoverSource.EMBEDDED]).
 */
data class PendingCover(
    val bytes: ByteArray,
    val mime: String,
    val source: CoverSource,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingCover) return false
        return mime == other.mime && source == other.source && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = mime.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
