package com.calypsan.listenup.server.scanner.inference

import com.calypsan.listenup.api.dto.scanner.TrackNumberSource

/**
 * Inferred track and disc numbers for one audio file. Either field can be
 * null when no signal is found; consumers use original filesystem order as
 * the fallback.
 */
internal data class TrackInfo(
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackSource: TrackNumberSource? = null,
    val discSource: TrackNumberSource? = null,
)

/**
 * Track and disc inference from a filename and its parent folder name.
 * Ported from ABS `server/scanner/AudioFileScanner.js:111-147`
 * (`getTrackAndDiscNumberFromFilename`).
 *
 * Disc number is sourced from (in priority order):
 *  1. `\b(disc|cd) ?(\d{1,2})\b` matched anywhere in the filename.
 *  2. `^(cd|dis[ck])\s*(\d{1,3})$` matched against the parent folder name
 *     (the multi-disc subdirectory convention).
 *
 * Track number is the first 1–4 digit run remaining in the filename after
 * stripping any matched disc prefix. Strict heuristic: ABS additionally
 * strips title/author/series/year text before this match, but this parser
 * skips that — embedded-tag-driven track inference (`TrackNumberSource.METADATA`)
 * is the better fix.
 */
internal object TrackInference {
    private val discInFilename = Regex("""\b(disc|cd) ?(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    // No `\b` boundaries: filenames like `track01` don't have a word boundary
    // between `k` and `0`, since both classes are "word" characters. Greedy
    // `\d{1,4}` matches the longest 1-4 digit run and bounds 5+ digit numbers
    // (e.g. `12345.mp3` → 1234, leaving `5` unmatched).
    private val trackPattern = Regex("""(\d{1,4})""")

    fun infer(
        filename: String,
        parentFolderName: String?,
    ): TrackInfo {
        val nameWithoutExt = filename.substringBeforeLast('.', filename)

        var discNumber: Int? = null
        var discSource: TrackNumberSource? = null
        var trackHaystack = nameWithoutExt

        // Disc from filename first.
        val discFilenameMatch = discInFilename.find(nameWithoutExt)
        if (discFilenameMatch != null) {
            discNumber = discFilenameMatch.groupValues[2].toIntOrNull()
            discSource = TrackNumberSource.FILENAME
            trackHaystack = nameWithoutExt.removeRange(discFilenameMatch.range)
        } else if (parentFolderName != null) {
            val folderDisc = MultiDiscPattern.discNumber(parentFolderName)
            if (folderDisc != null) {
                discNumber = folderDisc
                discSource = TrackNumberSource.FOLDER
            }
        }

        val trackMatch = trackPattern.find(trackHaystack)
        val trackNumber = trackMatch?.groupValues?.get(1)?.toIntOrNull()
        val trackSource = trackNumber?.let { TrackNumberSource.FILENAME }

        return TrackInfo(
            trackNumber = trackNumber,
            discNumber = discNumber,
            trackSource = trackSource,
            discSource = discSource,
        )
    }
}
