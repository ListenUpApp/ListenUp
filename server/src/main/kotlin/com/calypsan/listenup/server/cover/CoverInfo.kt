package com.calypsan.listenup.server.cover

import java.nio.file.Path

/**
 * Where a book's cover image lives — the resolved input the cover route needs
 * to produce image bytes.
 *
 * A book's cover is one of two kinds: a standalone image file in the library
 * directory, or artwork embedded inside the book's audio file. `BookRepository`
 * resolves the persisted `coverSource` column into the matching variant,
 * carrying an absolute filesystem [Path] in each case.
 */
sealed interface CoverInfo {
    /**
     * Stable content hash of the cover bytes, sourced from `book.cover_hash`.
     * Used by the cover route to set the HTTP `ETag` and short-circuit on
     * `If-None-Match`. Null when the book row has no hash recorded — the route
     * then serves bytes without caching headers rather than fabricating one.
     */
    val hash: String?

    /**
     * The cover is a standalone image file at [path] (an absolute path to a
     * `cover.*` image, or the first sibling image, inside the book directory).
     */
    data class Filesystem(
        val path: Path,
        override val hash: String?,
    ) : CoverInfo

    /**
     * The cover is artwork embedded in the book's primary audio file at
     * [audioFilePath] (an absolute path). The artwork bytes and MIME type are
     * not known until the file is parsed — extraction happens at serve time.
     */
    data class Embedded(
        val audioFilePath: Path,
        override val hash: String?,
    ) : CoverInfo
}
