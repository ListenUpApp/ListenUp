package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.BookCandidate
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.CoverCandidate
import com.calypsan.listenup.api.dto.match.CurrentCover
import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.FoundIn
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.RegionOrigin
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale

/** How many reasons a candidate row shows; the server orders them by how much they explain the tier. */
internal const val REASONS_SHOWN: Int = 3

/**
 * The book being matched, as this device already knows it — read from Room, so Find is never blank while the
 * server searches. Every value may be missing for a book the scanner knew little about.
 */
data class YourCopyUi(
    val title: String,
    val authors: List<String>,
    val coverPath: String?,
    val coverHash: String?,
    val durationMs: Long?,
    val narrators: List<String>,
    val chapterCount: Int?,
    val year: Int?,
    val isAbridged: Boolean,
)

/** One Find candidate as every platform renders it. [id] is a stable key for lists and test tags. */
data class CandidateUi(
    val id: String,
    val key: BookCandidateKey,
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val narrators: List<String>,
    val durationMs: Long?,
    val year: Int?,
    val format: EditionFormat?,
    val chapterCount: Int?,
    val coverUrl: String?,
    val foundIn: List<FoundIn>,
    val tier: MatchTier,
    val isBest: Boolean,
    val isCurrentLink: Boolean,
    /** At most [REASONS_SHOWN], in the server's order. */
    val reasons: List<MatchReason>,
)

/** The store Find searched in, the source that has stores, and the stores a person can switch to. */
data class RegionUi(
    val source: MetadataSource,
    val region: MetadataLocale,
    val origin: RegionOrigin,
    val choices: List<MetadataLocale>,
)

/** Some sources failed while others answered: "Hardcover didn't answer, so these results are from Audible and iTunes." */
data class PartialFailure(
    val failed: List<MetadataSource>,
    val answered: List<MetadataSource>,
)

/** Why Find has nothing to show. Chosen from the source statuses by one pure function ([chooseFindOutcome]). */
sealed interface FindFailure {
    /** This device can't reach the server. */
    data object Offline : FindFailure

    /** The deciding source didn't answer within its deadline. */
    data class TimedOut(
        val source: MetadataSource,
    ) : FindFailure

    /** The deciding source asked us to slow down; Retry stays disabled until [secondsRemaining] reaches 0. */
    data class RateLimited(
        val source: MetadataSource,
        val secondsRemaining: Int,
    ) : FindFailure

    /** The deciding source failed outright (not a timeout or a rate limit). */
    data class SourceFailed(
        val source: MetadataSource,
    ) : FindFailure

    /** The store answered with nothing; [suggestions] are at most two other stores to try. */
    data class NotFoundInStore(
        val source: MetadataSource,
        val region: MetadataLocale,
        val suggestions: List<MetadataLocale>,
    ) : FindFailure

    /** Every source answered and none had a match. */
    data object NothingFound : FindFailure

    /** Anything else; the typed error carries its own message. */
    data class Unexpected(
        val error: AppError,
    ) : FindFailure
}

/** The Find step of Match details. */
sealed interface FindUiState {
    /** The Your copy strip, or null for the instant before Room answers. */
    val yourCopy: YourCopyUi?

    /** A search is running; [previous] keeps the last results on screen so nothing jumps to blank. */
    data class Searching(
        override val yourCopy: YourCopyUi?,
        val query: String,
        val previous: Results?,
    ) : FindUiState

    /** Candidates to choose from, Strong first. [pickedKey] is the row last opened in Review. */
    data class Results(
        override val yourCopy: YourCopyUi?,
        val steps: List<SearchStep>,
        val query: String,
        val strong: List<CandidateUi>,
        val maybe: List<CandidateUi>,
        val partialFailure: PartialFailure?,
        val region: RegionUi?,
        val pickedKey: BookCandidateKey?,
    ) : FindUiState {
        /** Every candidate, Strong then Maybe. */
        val all: List<CandidateUi> get() = strong + maybe
    }

    /** Nothing to show, and why. */
    data class Failed(
        override val yourCopy: YourCopyUi?,
        val query: String,
        val failure: FindFailure,
        val region: RegionUi?,
    ) : FindUiState
}

/** One option a field can take, with every source that offered it. */
data class FieldOptionUi(
    val optionId: String,
    val value: FieldValue,
    val sources: List<MetadataSource>,
)

/**
 * One reviewable field: Yours → Proposed. [choice] is the checkbox and the source switch as one value — unticked
 * is [FieldChoice.KeepCurrent]. [proposed] is the option currently chosen, or the one re-ticking would restore.
 */
data class FieldUi(
    val field: BookField,
    val state: FieldState,
    val current: FieldValue?,
    val options: List<FieldOptionUi>,
    val choice: FieldChoice,
    val proposed: FieldOptionUi,
    val handEdit: HandEdit?,
) {
    /** Whether Apply writes this field. */
    val isTicked: Boolean get() = choice is FieldChoice.Option

    /** Whether a "Keep yours" segment belongs in the source switch: only when there is something to keep. */
    val canKeepYours: Boolean get() = current != null
}

/** The cover decision: Keep current (your actual cover) or one candidate. */
data class CoverUi(
    val current: CurrentCover?,
    val currentCoverPath: String?,
    val options: List<CoverCandidate>,
    val choice: ImageChoice,
) {
    /** The candidate Apply writes, or null for Keep current. */
    val chosen: CoverCandidate?
        get() = (choice as? ImageChoice.Candidate)?.let { c -> options.firstOrNull { it.optionId == c.optionId } }
}

/** Genres or moods. */
enum class LabelKind {
    GENRES,
    MOODS,
}

/** One of your labels; [removed] once you tapped its ×. */
data class YourLabelUi(
    val label: String,
    val removed: Boolean,
)

/** A suggested label and every source that offered it; all start [selected]. */
data class SuggestionUi(
    val label: String,
    val sources: List<MetadataSource>,
    val selected: Boolean,
)

/** "Yours, kept" and "Suggested" for one label kind. */
data class LabelSetUi(
    val yours: List<YourLabelUi>,
    val suggested: List<SuggestionUi>,
) {
    /** Labels Apply adds. */
    val added: List<String> get() = suggested.filter { it.selected }.map { it.label }

    /** Labels Apply removes. */
    val removedLabels: List<String> get() = yours.filter { it.removed }.map { it.label }

    /** Whether Apply changes this set. */
    val changes: Boolean get() = added.isNotEmpty() || removedLabels.isNotEmpty()
}

/** One chapter whose name differs. */
data class ChapterRowUi(
    val ordinal: Int,
    val yours: String,
    val theirs: String,
    val selected: Boolean,
)

/** The chapter-names section. */
sealed interface ChapterNamesUi {
    /** No source has names for this edition; the section isn't rendered. */
    data object Hidden : ChapterNamesUi

    /** A different chapter count: a different edition. Shown, never applied. */
    data class CountMismatch(
        val source: MetadataSource,
        val yours: Int,
        val theirs: Int,
    ) : ChapterNamesUi

    /** Names to apply: [rows] differ, [unchangedCount] already match. */
    data class Available(
        val source: MetadataSource,
        val rows: List<ChapterRowUi>,
        val unchangedCount: Int,
        val included: Boolean,
    ) : ChapterNamesUi {
        /** How many names Apply writes. */
        val applyCount: Int get() = if (included) rows.count { it.selected } else 0
    }
}

/** The counts at the top of Review; each jumps to its section. */
data class WhatWillChange(
    val coverSource: MetadataSource?,
    val changeCount: Int,
    val gapCount: Int,
    val labelsAdded: Int,
    val labelsRemoved: Int,
    val chapterNameCount: Int,
    val keptEditedCount: Int,
)

/** The sticky Apply bar: "5 fields · cover · 16 chapter names". */
data class ApplySummary(
    val fieldCount: Int,
    val coverChanges: Boolean,
    val chapterNameCount: Int,
) {
    /** Whether Apply would change anything at all. */
    val canApply: Boolean get() = fieldCount > 0 || coverChanges || chapterNameCount > 0
}

/** The Review step of Match details. */
sealed interface ReviewUiState {
    /** Nothing picked yet: phones show Find alone, two panes show a placeholder. */
    data object NoneChosen : ReviewUiState

    /** Loading the Review for [candidate]. */
    data class Loading(
        val candidate: CandidateUi,
    ) : ReviewUiState

    /**
     * Everything Apply would do, in canvas order: cover, changes, fills a gap, you edited this, genres & moods,
     * chapter names, already the same. Empty sections aren't rendered.
     */
    data class Ready(
        val candidate: CandidateUi,
        val summary: WhatWillChange,
        val cover: CoverUi,
        val changes: List<FieldUi>,
        val fillsGap: List<FieldUi>,
        val youEdited: List<FieldUi>,
        val genres: LabelSetUi,
        val moods: LabelSetUi,
        val chapterNames: ChapterNamesUi,
        val alreadySame: List<BookField>,
        val lengthAlreadySame: Boolean,
        val applyBar: ApplySummary,
        val applying: Boolean,
        val applyError: AppError?,
    ) : ReviewUiState

    /** The Review couldn't load. */
    data class Failed(
        val candidate: CandidateUi,
        val error: AppError,
    ) : ReviewUiState
}

/** One-shot outcomes of Match details. */
sealed interface BookMatchEvent {
    /** Apply committed; every layout returns to Book Detail, which shows the receipt. */
    data class Applied(
        val receipt: MatchReceipt,
    ) : BookMatchEvent

    /** Apply found the book changed; Review reloaded with the choices that survived. */
    data object ReviewReloaded : BookMatchEvent
}

/** The receipt as Book Detail shows it: "Changed 5 fields, cover from Hardcover, 16 chapter names". */
data class MatchReceiptUi(
    val receiptId: String,
    val fieldCount: Int,
    val coverSource: MetadataSource?,
    val chapterNameCount: Int,
    val changes: List<AppliedChange>,
    val undoable: Boolean,
)

internal fun BookCandidate.toUi(): CandidateUi =
    CandidateUi(
        id = key.stableId(),
        key = key,
        title = title,
        subtitle = subtitle,
        authors = authors,
        narrators = narrators,
        durationMs = durationMs,
        year = year,
        format = format,
        chapterCount = chapterCount,
        coverUrl = coverUrl,
        foundIn = foundIn,
        tier = tier,
        isBest = isBest,
        isCurrentLink = isCurrentLink,
        reasons = reasons.take(REASONS_SHOWN),
    )

/** A key's refs as one stable string, so the same candidate has the same id on every Find. */
internal fun BookCandidateKey.stableId(): String =
    refs
        .map { "${it.provider}:${it.id}:${it.region.orEmpty()}" }
        .sorted()
        .joinToString("|")

/**
 * The receipt's counts. The field count is the scalar and list fields written plus one if genres changed and one
 * if moods changed — the canvas's "5 fields" (description, publisher, release date, genres, moods).
 */
fun MatchReceipt.toUi(): MatchReceiptUi =
    MatchReceiptUi(
        receiptId = receiptId,
        fieldCount =
            changes.count {
                it is AppliedChange.Field || it is AppliedChange.Genres || it is AppliedChange.Moods ||
                    it is AppliedChange.Biography
            },
        coverSource = changes.filterIsInstance<AppliedChange.Cover>().firstOrNull()?.source,
        chapterNameCount = changes.filterIsInstance<AppliedChange.ChapterNames>().sumOf { it.count },
        changes = changes,
        undoable = undoable,
    )
