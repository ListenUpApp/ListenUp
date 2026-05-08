package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata

/**
 * Strips a leading track-number-or-disc-number prefix from a filename stem. Matches:
 *   `01 `, `01_`, `01-01 `, bare `01` (entire stem), `Track 01 - `,
 *   `Disc 1 Track 02_`, `CD02-04 `.
 * Doesn't match `Chapter_3_…` — so chapter info in filenames survives cleanup.
 *
 * Pattern breakdown:
 *   - Optional leading word: `disc`, `cd`, or `track` (case-insensitive) + optional space
 *   - Required first number (1–3 digits)
 *   - Optional middle section: separator + optional word + second number
 *   - Optional trailing separator (zero or more, so bare numbers at end-of-stem match)
 *
 * Source: spec §5.1 of `2026-05-07-phase-4-multifile-chapter-synthesis-design.md`.
 */
private val FILENAME_PREFIX_REGEX =
    Regex(
        """^(?:(?:disc|cd|track)\s*)?""" +   // optional leading word
            """\d{1,3}""" +                   // first number
            """(?:[\s\-_.]+(?:(?:disc|cd|track)\s*)?\d{1,3})?""" + // optional: sep [word] second-number
            """[\s\-_.]*""",                  // optional trailing separator
        RegexOption.IGNORE_CASE,
    )

private val WHITESPACE_RUN_REGEX = Regex("\\s+")

/**
 * Pick a chapter title for one synthesized chapter.
 *
 * Precedence (spec §5):
 *  1. Track's TIT2 if non-blank and case-insensitive-trimmed not equal to [bookTitle].
 *  2. Cleaned filename: strip extension, strip leading track-number prefix
 *     (see [FILENAME_PREFIX_REGEX]), replace `_` with ` `, collapse whitespace,
 *     trim. Falls through if empty or equal to book title.
 *  3. `"Track N"` where N is [trackIndex] (1-based stable-sort position).
 */
internal fun pickChapterTitle(
    track: TrackEntry,
    bookTitle: String,
    trackMeta: EmbeddedAudioMetadata?,
    trackIndex: Int,
): String {
    val normalizedBook = bookTitle.trim().lowercase()

    // Rule 1: track TIT2 if meaningfully different from book title.
    trackMeta
        ?.tags
        ?.title
        ?.takeIf { it.isNotBlank() && it.trim().lowercase() != normalizedBook }
        ?.let { return it }

    // Rule 2: cleaned filename if non-empty and non-equal-to-book-title.
    cleanFilename(track.file.name)
        .takeIf { it.isNotEmpty() && it.lowercase() != normalizedBook }
        ?.let { return it }

    // Rule 3: "Track N" fallback.
    return "Track $trackIndex"
}

internal fun cleanFilename(name: String): String =
    name
        .substringBeforeLast('.')
        .replaceFirst(FILENAME_PREFIX_REGEX, "")
        .replace('_', ' ')
        .replace(WHITESPACE_RUN_REGEX, " ")
        .trim()

/**
 * Build one [Chapter] per track. A track whose duration parse failed
 * (`perTrackMetadata[track]` is null or `durationMs == 0`) produces a
 * zero-length chapter at the current cumulative position; subsequent
 * tracks' boundaries are unaffected by the failure.
 *
 * Source: spec §6 of `2026-05-07-phase-4-multifile-chapter-synthesis-design.md`.
 */
internal fun synthesizeChapters(
    tracks: List<TrackEntry>,
    perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?>,
    bookTitle: String,
): List<Chapter> {
    var cumulativeMs = 0L
    return tracks.mapIndexed { i, track ->
        val durationMs = perTrackMetadata[track]?.durationMs ?: 0L
        val chapter =
            Chapter(
                index = i + 1,
                title = pickChapterTitle(track, bookTitle, perTrackMetadata[track], i + 1),
                startMs = cumulativeMs,
                endMs = cumulativeMs + durationMs,
            )
        cumulativeMs += durationMs
        chapter
    }
}
