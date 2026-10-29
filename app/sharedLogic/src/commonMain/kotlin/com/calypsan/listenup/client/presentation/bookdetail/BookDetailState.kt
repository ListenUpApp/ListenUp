package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
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
    /** The player's loaded book on this device, if any: the current chapter says "Now playing" only for it. */
    val loadedBookId: String? = null,
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

    val stage = readingStage(position, detail.duration)
    val isComplete = stage == ReadingStage.Finished
    val hasMeaningfulProgress = stage == ReadingStage.InProgress
    val positionMs = position?.positionMs ?: 0L

    val progress =
        if (hasMeaningfulProgress) (positionMs.toFloat() / detail.duration).coerceIn(0f, 1f) else null

    val current =
        if (hasMeaningfulProgress) {
            currentChapterOf(chapters, positionMs)?.copy(isInPlayer = ambient.loadedBookId == detail.id.value)
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
                isCurrent = index == current?.index,
                startMs = domainChapter.startTime,
                durationMs = domainChapter.duration,
            )
        }

    val timeRemainingMs = if (hasMeaningfulProgress) (detail.duration - positionMs).coerceAtLeast(0L) else null
    val canPlay = availability.canPlay && !isHeld
    val showServerWarning = availability.showServerWarning && !isHeld

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
        progress = progress,
        timeRemainingFormatted = timeRemainingMs?.let { DurationFormatter.timeLeft(it.milliseconds) },
        addedAt = detail.addedAt.epochMillis,
        hasScanWarning = detail.hasScanWarning,
        genres = detail.genres,
        tags = detail.tags,
        moods = detail.moods,
        downloadStatus = availability.downloadStatus,
        isPlaybackAvailable = availability.isPlaybackAvailable,
        canPlay = canPlay,
        canDownload = availability.canDownload && !isHeld,
        showServerWarning = showServerWarning,
        isWaitingForWifi = availability.isWaitingForWifi,
        isHeld = isHeld,
        visibility = visibility,
        readingStage = stage,
        sectionOrder =
            sectionOrderFor(
                stage,
                SectionPresence(hasChapters = chapters.isNotEmpty()),
            ),
        primaryAction = primaryActionFor(stage, showServerWarning, canPlay),
        currentChapter = current,
        chapterPreview = chapterWindow(chapterRows, current?.index),
        timeRemainingMs = timeRemainingMs,
        finishedSpan =
            position
                ?.finishedAtMs
                ?.takeIf {
                    isComplete
                }?.let { FinishedSpan(startedAtMs = position.startedAtMs, finishedAtMs = it) },
        downloadSizeBytes = detail.audioFiles.sumOf { it.size },
        showScanWarning = detail.hasScanWarning && ambient.isAdmin,
    )
}

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

/** Where loading the requested book stands. Loading itself is not a value here: it is any load of another id. */
internal sealed interface BookLoad {
    /** The book this load is about. */
    val bookId: String

    /** Room has no row for [bookId]. */
    data class Missing(
        override val bookId: String,
    ) : BookLoad

    /** Room's latest word on [bookId]. */
    data class Loaded(
        override val bookId: String,
        val snapshot: BookSnapshot,
    ) : BookLoad
}

/**
 * The reader's transient state on one book — open pickers, writes in flight, inline refusals — kept apart from what
 * Room says, so Room re-emitting (a sync frame, a download tick, a reachability flip) can never close a picker
 * mid-interaction. Keyed by [visit], one number per visit to a book: switching books starts a fresh overlay with the
 * next number, and a write that answers after the reader has left — even if they have since come back to the same
 * book — cannot reach the visit they are on now.
 *
 * A new user-transient field on [BookDetailUiState.Ready] goes here and into [withOverlay].
 */
internal data class BookDetailOverlay(
    val bookId: String?,
    val visit: Int = 0,
    val isMarkingComplete: Boolean = false,
    val isDiscardingProgress: Boolean = false,
    val isRestarting: Boolean = false,
    val showShelfPicker: Boolean = false,
    val isAddingToShelf: Boolean = false,
    val shelfError: String? = null,
    val showCollectionPicker: Boolean = false,
    val isAddingToCollection: Boolean = false,
    val collectionError: String? = null,
    val isDeletingBook: Boolean = false,
    val deleteError: AppError? = null,
    val isReleasingFromInbox: Boolean = false,
    val isRestoringToAllBooks: Boolean = false,
) {
    /**
     * What a fresh Room emission for [load] retires: "restoring"
     * once the book is no longer stranded — for good, so being stranded again later does not resurrect it. A
     * vanished row clears everything, as the old Error-breaks-the-chain behaviour did. Another book's load is
     * none of this overlay's business.
     */
    fun retiredBy(load: BookLoad): BookDetailOverlay =
        when {
            load.bookId != bookId -> {
                this
            }

            load is BookLoad.Missing -> {
                BookDetailOverlay(bookId = bookId, visit = visit)
            }

            else -> {
                val stillStranded = (load as BookLoad.Loaded).snapshot.visibility is BookVisibility.Stranded
                copy(isRestoringToAllBooks = isRestoringToAllBooks && stillStranded)
            }
        }
}

/**
 * Room's latest word and the reader's overlay, held as one value so they always change together: a Room emission
 * writes the load and the overlay it retires in a single step, and nothing — neither the screen nor an action's
 * guard — can see the retired overlay beside the load before it.
 */
internal data class BookDetailPage(
    val load: BookLoad?,
    val overlay: BookDetailOverlay,
    val orderLatch: SectionOrderLatch? = null,
) {
    /** The page once Room has said [newLoad]. */
    fun withLoad(newLoad: BookLoad): BookDetailPage =
        copy(load = newLoad, overlay = overlay.retiredBy(newLoad)).latched()

    /**
     * The page once the reader asks for [bookId]: the same book keeps whatever is open; another book starts the next
     * visit with nothing open. The load stays, so coming back to a book that is still loaded shows it at once.
     */
    fun visiting(bookId: String): BookDetailPage =
        if (overlay.bookId == bookId) {
            this
        } else {
            copy(overlay = BookDetailOverlay(bookId = bookId, visit = overlay.visit + 1)).latched()
        }

    /** Takes this visit's section order from its first load of the visited book, once. */
    private fun latched(): BookDetailPage {
        val loaded = load as? BookLoad.Loaded ?: return this
        val bookId = overlay.bookId
        if (loaded.bookId != bookId) return this
        if (orderLatch?.bookId == bookId && orderLatch.visit == overlay.visit) return this
        val order = loaded.snapshot.toReady(BookDetailAmbient()).sectionOrder
        return copy(orderLatch = SectionOrderLatch(bookId = bookId, visit = overlay.visit, order = order))
    }
}

/**
 * The section order taken at the first load of one visit to [bookId] (spec §2.4 rule 2). Held beside the overlay
 * rather than in it: it is not something the reader did, and nothing but a new visit replaces it.
 */
internal data class SectionOrderLatch(
    val bookId: String,
    val visit: Int,
    val order: List<BookDetailSection>,
)

/** This page with the reader's [overlay] laid on it. */
internal fun BookDetailUiState.Ready.withOverlay(overlay: BookDetailOverlay): BookDetailUiState.Ready =
    copy(
        isMarkingComplete = overlay.isMarkingComplete,
        isDiscardingProgress = overlay.isDiscardingProgress,
        isRestarting = overlay.isRestarting,
        showShelfPicker = overlay.showShelfPicker,
        isAddingToShelf = overlay.isAddingToShelf,
        shelfError = overlay.shelfError,
        showCollectionPicker = overlay.showCollectionPicker,
        isAddingToCollection = overlay.isAddingToCollection,
        collectionError = overlay.collectionError,
        isDeletingBook = overlay.isDeletingBook,
        deleteError = overlay.deleteError,
        isReleasingFromInbox = overlay.isReleasingFromInbox,
        // The echo that re-homes a stranded book is exactly what ends "restoring".
        isRestoringToAllBooks = overlay.isRestoringToAllBooks && visibility is BookVisibility.Stranded,
    )

/**
 * Book Detail's whole screen state. `Loading` whenever the latest [load] is not for [requestedBookId], so a page
 * can only ever describe the book that was asked for; the [overlay] applies only to that book.
 */
internal fun bookDetailUiState(
    requestedBookId: String?,
    load: BookLoad?,
    ambient: BookDetailAmbient,
    overlay: BookDetailOverlay,
    orderLatch: SectionOrderLatch?,
): BookDetailUiState {
    if (requestedBookId == null || load == null || load.bookId != requestedBookId) return BookDetailUiState.Loading
    return when (load) {
        is BookLoad.Missing -> {
            BookDetailUiState.Error(BookError.NotFound())
        }

        is BookLoad.Loaded -> {
            val own = overlay.takeIf { it.bookId == requestedBookId } ?: BookDetailOverlay(requestedBookId)
            val latched = orderLatch?.takeIf { it.bookId == requestedBookId && it.visit == own.visit }?.order
            val ready = load.snapshot.toReady(ambient).withOverlay(own)
            if (latched == null) ready else ready.copy(sectionOrder = latched)
        }
    }
}
