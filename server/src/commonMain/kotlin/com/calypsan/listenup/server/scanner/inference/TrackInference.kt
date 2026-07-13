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
 *  1. `\b(disc|cd) ?(\d{1,3})\b` matched anywhere in the filename.
 *  2. `^(cd|dis[ck])\s*(\d{1,3})$` matched against the parent folder name
 *     (the multi-disc subdirectory convention).
 *
 * Track number is the **last** complete 1–4 digit run remaining in the
 * filename after stripping any matched disc prefix. Audiobook filenames
 * conventionally place the sequence number after the title
 * (`"1984 - 12.mp3"`, `"Title - 05.mp3"`), so a leading numeric token is
 * usually a year or title text; taking the last run avoids inferring `1984`
 * as the track for every file in a book named after a year. When a genuine
 * `"NN - Title"` prefix is the only run, last == first, so the leading-number
 * convention still works. A run of 5+ digits is not a track number and is
 * ignored entirely. ABS additionally strips title/author/series/year text
 * before this match; embedded-tag inference (`TrackNumberSource.METADATA`) is
 * the stronger fix and already wins when present.
 */
internal object TrackInference {
    // `\d{1,3}` matches MultiDiscPattern's folder-disc range so 100+-disc sets are handled
    // consistently whether the disc number is in the filename or the parent folder name.
    private val discInFilename = Regex("""\b(disc|cd) ?(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    // Complete digit runs (maximal, delimited by non-digits). The consumer
    // picks the last run of 1-4 digits; a 5+ digit run is skipped, not
    // truncated, since a long numeric blob is not a track number.
    private val digitRun = Regex("""\d+""")

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

        val trackNumber =
            digitRun
                .findAll(trackHaystack)
                .map { it.value }
                .lastOrNull { it.length in 1..4 }
                ?.toIntOrNull()
        val trackSource = trackNumber?.let { TrackNumberSource.FILENAME }

        return TrackInfo(
            trackNumber = trackNumber,
            discNumber = discNumber,
            trackSource = trackSource,
            discSource = discSource,
        )
    }
}
