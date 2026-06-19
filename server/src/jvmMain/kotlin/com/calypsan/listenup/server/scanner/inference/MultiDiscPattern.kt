package com.calypsan.listenup.server.scanner.inference

/**
 * The ABS multi-disc folder convention: a directory whose name matches
 * `^(cd|dis[ck])\s*\d{1,3}$` (case-insensitive) is treated as a disc
 * subdirectory of its parent book, not a book root in its own right.
 *
 * Source: `audiobookshelf/server/utils/scandir.js:93-97`.
 *
 * Two stages need this rule:
 *  - the [com.calypsan.listenup.server.scanner.pipeline.Grouper] uses
 *    [matches] to roll a disc folder up to its parent book;
 *  - [TrackInference] uses [discNumber] to extract the disc number when a
 *    file's parent folder is a disc subdirectory.
 *
 * Keeping one regex avoids the two stages drifting apart on what counts
 * as "a disc folder."
 */
internal object MultiDiscPattern {
    private val pattern = Regex("""^(cd|dis[ck])\s*(\d{1,3})$""", RegexOption.IGNORE_CASE)

    fun matches(folderName: String): Boolean = pattern.matches(folderName)

    fun discNumber(folderName: String): Int? =
        pattern
            .matchEntire(folderName)
            ?.groupValues
            ?.get(2)
            ?.toIntOrNull()
}
