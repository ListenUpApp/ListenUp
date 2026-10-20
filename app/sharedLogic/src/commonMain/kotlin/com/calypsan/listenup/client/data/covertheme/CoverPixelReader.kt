package com.calypsan.listenup.client.data.covertheme

import com.calypsan.listenup.core.BookId

/** A cover downsampled so its longer side is at most the requested edge: ARGB, row-major, [width] × [height]. */
internal class CoverPixels(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

/** The platform's half of the cover colour rule (Book Detail spec §4.4): decoding a book's cover into pixels. */
internal interface CoverPixelReader {
    /**
     * The cover of [bookId] at [coverHash], downsampled to [maxEdge], or `null` when there is no readable cover (not
     * downloaded yet, offline, unauthorised, undecodable). Never throws except to cancel.
     */
    suspend fun read(
        bookId: BookId,
        coverHash: String?,
        maxEdge: Int,
    ): CoverPixels?
}
