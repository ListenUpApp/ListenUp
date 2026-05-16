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
     * The cover is a standalone image file at [path] (an absolute path to a
     * `cover.*` image, or the first sibling image, inside the book directory).
     */
    data class Filesystem(
        val path: Path,
    ) : CoverInfo

    /**
     * The cover is artwork embedded in the book's primary audio file at
     * [audioFilePath] (an absolute path). The artwork bytes and MIME type are
     * not known until the file is parsed — extraction happens at serve time.
     */
    data class Embedded(
        val audioFilePath: Path,
    ) : CoverInfo
}
