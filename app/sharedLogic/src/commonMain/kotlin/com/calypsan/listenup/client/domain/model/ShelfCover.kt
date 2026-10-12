package com.calypsan.listenup.client.domain.model

/**
 * One tile of a shelf's cover mosaic: a book the shelf names and how this device can draw it.
 *
 * Android and iOS draw from [coverPath] (versioned by [coverHash]); the browser holds no files and
 * loads the cover by [bookId]. A shelf names a book this device does not hold only to have it skipped,
 * so every tile here is a book the reader can open.
 *
 * @property bookId The book this tile shows.
 * @property coverPath The local cover file, or null when the cover is not downloaded here.
 * @property coverHash The cover's content version, or null when the book has no cover.
 */
data class ShelfCover(
    val bookId: String,
    val coverPath: String?,
    val coverHash: String?,
)
