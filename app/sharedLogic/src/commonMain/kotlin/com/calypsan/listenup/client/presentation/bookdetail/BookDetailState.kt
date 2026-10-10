package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.core.formatSeriesSequence
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookAvailability
import kotlin.time.Duration.Companion.milliseconds

/**
 * Everything the device knows about one book at one moment: the row, its chapters, where you are in it, whether
 * it can play here, whether it is held for review, and who cannot see it. [BookDetailUiState.Ready] is a pure
 * function of this, the [BookDetailAmbient] facts and the reader's [BookDetailOverlay].
 */
internal data class BookSnapshot(
    val detail: BookDetail,
    val chapters: List<Chapter>,
    val position: PlaybackPosition?,
    val availability: BookAvailability.State,
    val isHeld: Boolean,
    val visibility: BookVisibility?,
)

/** What Book Detail shows that does not depend on which book is open: who you are, and the library's shape. */
internal data class BookDetailAmbient(
    val isAdmin: Boolean = false,
    val canEditMetadata: Boolean = false,
    val hierarchy: SeriesHierarchy = SeriesHierarchy.Empty,
    val allTags: List<Tag> = emptyList(),
)

/**
 * The detail page for this snapshot, before the reader's overlay is laid on it.
 *
 * A held book is triage-only: no play, no download, and no "server unreachable" nag about a playback that cannot
 * happen. They are forced off here so a platform that misses the triage layout still cannot offer a working Play
 * or Download; the choke points refuse regardless.
 */
internal fun BookSnapshot.toReady(ambient: BookDetailAmbient): BookDetailUiState.Ready {
    // Filter out subtitles that just restate a series the book belongs to (name, or name + book number) —
    // checked against every membership now that a book can be in several series.
    val displaySubtitle =
        detail.subtitle?.takeUnless { subtitle ->
            detail.series.any {
                // isSubtitleRedundant is a text heuristic and still takes text; the number is formatted for it
                // here rather than the function learning about Doubles.
                isSubtitleRedundant(subtitle, it.seriesName, it.sequence?.let(::formatSeriesSequence))
            }
        }

    val progress =
        if (position != null && detail.duration > 0) {
            (position.positionMs.toFloat() / detail.duration).coerceIn(0f, 1f)
        } else {
            null
        }

    // Authoritative completion flag from the saved position
    val isComplete = position?.isFinished == true

    val hasMeaningfulProgress = progress != null && progress > 0f && !isComplete

    // Resolve the current-chapter highlight only once the position is known. An un-started book (no meaningful
    // progress) highlights nothing.
    val currentIdx =
        if (hasMeaningfulProgress) {
            currentChapterIndex(chapters.map { it.startTime }, position?.positionMs ?: 0L)
        } else {
            null
        }

    val chapterRows =
        chapters.mapIndexed { index, domainChapter ->
            ChapterUiModel(
                id = domainChapter.id,
                title = domainChapter.title,
                duration = domainChapter.formatDuration(),
                imageUrl = null, // Placeholder
                isCurrent = index == currentIdx,
                startMs = domainChapter.startTime,
                durationMs = domainChapter.duration,
            )
        }

    val timeRemaining =
        if (hasMeaningfulProgress) {
            val remainingMs = detail.duration - (position?.positionMs ?: 0L)
            DurationFormatter.timeLeft(remainingMs.milliseconds)
        } else {
            null
        }

    return BookDetailUiState.Ready(
        book = detail,
        isAdmin = ambient.isAdmin,
        canEditMetadata = ambient.canEditMetadata,
        allTags = ambient.allTags,
        isComplete = isComplete,
        startedAtMs = position?.startedAtMs,
        subtitle = displaySubtitle,
        seriesPaths = bookSeriesPaths(detail.series, ambient.hierarchy),
        descriptionText = detail.description.orEmpty(),
        narrators = detail.narratorNames,
        year = detail.publishYear,
        chapters = chapterRows,
        progress = if (hasMeaningfulProgress) progress else null,
        timeRemainingFormatted = timeRemaining,
        addedAt = detail.addedAt.epochMillis,
        hasScanWarning = detail.hasScanWarning,
        genres = detail.genres,
        tags = detail.tags,
        moods = detail.moods,
        downloadStatus = availability.downloadStatus,
        isPlaybackAvailable = availability.isPlaybackAvailable,
        canPlay = availability.canPlay && !isHeld,
        canDownload = availability.canDownload && !isHeld,
        showServerWarning = availability.showServerWarning && !isHeld,
        isWaitingForWifi = availability.isWaitingForWifi,
        isHeld = isHeld,
        visibility = visibility,
    )
}

/**
 * Index of the chapter currently playing: the last chapter whose start time is at or
 * before [positionMs], or null when there are no chapters. Pure — drives the
 * current-chapter highlight from playback position.
 */
internal fun currentChapterIndex(
    chapterStartTimesMs: List<Long>,
    positionMs: Long,
): Int? = chapterStartTimesMs.indexOfLast { it <= positionMs }.takeIf { it >= 0 }

/**
 * Checks if a subtitle is redundant because it's just the series name and book number.
 *
 * Examples of redundant subtitles:
 * - "The Stormlight Archive, Book 1"
 * - "Mistborn #3"
 * - "Book 2 of The Wheel of Time"
 *
 * The heuristic removes the series name and common book number patterns,
 * then checks if there's any meaningful content left.
 */
private fun isSubtitleRedundant(
    subtitle: String,
    seriesName: String?,
    seriesSequence: String?,
): Boolean {
    // If no series info, subtitle is not redundant
    if (seriesName.isNullOrBlank()) return false

    val normalizedSubtitle = subtitle.lowercase().trim()
    val normalizedSeriesName = seriesName.lowercase().trim()

    // Check if subtitle contains the series name
    if (!normalizedSubtitle.contains(normalizedSeriesName)) return false

    // Remove series name from subtitle
    var remaining = normalizedSubtitle.replace(normalizedSeriesName, "")

    // Remove common book number patterns
    val bookNumberPatterns =
        listOf(
            // "Book 1", "Book One", "Book I"
            Regex(
                """book\s*[#]?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten|i{1,3}|iv|v|vi{0,3}|ix|x)""",
                RegexOption.IGNORE_CASE,
            ),
            // "#1", "# 1"
            Regex("""#\s*\d+"""),
            // "Part 1", "Part One"
            Regex("""part\s*[#]?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)""", RegexOption.IGNORE_CASE),
            // "Volume 1", "Vol. 1", "Vol 1"
            Regex("""vol(ume|\.?)?\s*[#]?\s*\d+""", RegexOption.IGNORE_CASE),
            // Just a number (if sequence matches)
            seriesSequence?.let { Regex("""\b${Regex.escape(it)}\b""") },
        ).filterNotNull()

    for (pattern in bookNumberPatterns) {
        remaining = remaining.replace(pattern, "")
    }

    // Remove common separators and punctuation
    remaining =
        remaining
            .replace(Regex("""[,.:;|\-–—/\\()\[\]{}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    // If very little meaningful content remains (less than 3 chars), it's redundant
    return remaining.length < 3
}
