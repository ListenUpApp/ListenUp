package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.CoverCandidate
import com.calypsan.listenup.api.dto.match.CurrentCover
import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.FoundIn
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.RegionOrigin
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.ApplySummary
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.ChapterNamesUi
import com.calypsan.listenup.client.presentation.match.ChapterRowUi
import com.calypsan.listenup.client.presentation.match.CoverUi
import com.calypsan.listenup.client.presentation.match.FieldOptionUi
import com.calypsan.listenup.client.presentation.match.FieldUi
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.LabelSetUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.PartialFailure
import com.calypsan.listenup.client.presentation.match.RegionUi
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.client.presentation.match.SuggestionUi
import com.calypsan.listenup.client.presentation.match.WhatWillChange
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import com.calypsan.listenup.client.presentation.match.YourLabelUi

/*
 * Match details as the shared ViewModel would hand it to web: plain values, built here so each spec
 * names only what it is about. Provider labels are test data, not UI code.
 */

internal val AUDIBLE = MetadataSource(id = "src-a", label = "Audible")
internal val HARDCOVER = MetadataSource(id = "src-h", label = "Hardcover")
internal val ITUNES = MetadataSource(id = "src-i", label = "iTunes")

internal fun yourCopy(
    title: String = "Project Hail Mary",
    durationMs: Long? = 58_200_000,
    narrators: List<String> = listOf("Ray Porter"),
    chapterCount: Int? = 36,
): YourCopyUi =
    YourCopyUi(
        title = title,
        authors = listOf("Andy Weir"),
        coverPath = null,
        coverHash = "h1",
        durationMs = durationMs,
        narrators = narrators,
        chapterCount = chapterCount,
        year = 2021,
        isAbridged = false,
    )

internal fun candidate(
    id: String = "B1",
    title: String = "Project Hail Mary",
    tier: MatchTier = MatchTier.STRONG,
    isBest: Boolean = false,
    isCurrentLink: Boolean = false,
    foundIn: List<FoundIn> = listOf(FoundIn(AUDIBLE, "us"), FoundIn(HARDCOVER)),
    reasons: List<MatchReason> =
        listOf(MatchReason.SameNarrator, MatchReason.SameLength, MatchReason.SameChapterCount(36)),
    durationMs: Long? = 58_200_000,
    narrators: List<String> = listOf("Ray Porter"),
    chapterCount: Int? = 36,
): CandidateUi =
    CandidateUi(
        id = "audible:$id:us",
        key = BookCandidateKey(listOf(ExternalRef("audible", id, "us"))),
        title = title,
        subtitle = null,
        authors = listOf("Andy Weir"),
        narrators = narrators,
        durationMs = durationMs,
        year = 2021,
        format = EditionFormat.UNABRIDGED,
        chapterCount = chapterCount,
        coverUrl = null,
        foundIn = foundIn,
        tier = tier,
        isBest = isBest,
        isCurrentLink = isCurrentLink,
        reasons = reasons,
    )

internal val US = MetadataLocale("us")
internal val UK = MetadataLocale("uk")
internal val CA = MetadataLocale("ca")

internal fun region(): RegionUi = RegionUi(AUDIBLE, US, RegionOrigin.LIBRARY, listOf(US, UK, CA))

internal fun results(
    strong: List<CandidateUi> = listOf(candidate(isBest = true)),
    maybe: List<CandidateUi> =
        listOf(
            candidate(
                id = "B2",
                title = "Project Hail Mary (Abridged)",
                tier = MatchTier.MAYBE,
                reasons = listOf(MatchReason.LengthDiffers(-386)),
            ),
        ),
    partialFailure: PartialFailure? = null,
    steps: List<SearchStep> = listOf(SearchStep.ExistingLink(AUDIBLE), SearchStep.TitleAuthorLength),
    pickedKey: BookCandidateKey? = null,
    copy: YourCopyUi? = yourCopy(),
): FindUiState.Results =
    FindUiState.Results(
        yourCopy = copy,
        steps = steps,
        query = copy?.title.orEmpty(),
        strong = strong,
        maybe = maybe,
        partialFailure = partialFailure,
        region = region(),
        pickedKey = pickedKey,
    )

internal fun failed(failure: FindFailure): FindUiState.Failed =
    FindUiState.Failed(yourCopy = yourCopy(), query = "Project Hail Mary", failure = failure, region = region())

internal fun searching(previous: FindUiState.Results? = null): FindUiState.Searching =
    FindUiState.Searching(yourCopy = yourCopy(), query = "Project Hail Mary", previous = previous)

internal fun field(
    field: BookField,
    state: FieldState = FieldState.CHANGES,
    current: FieldValue? = FieldValue.Text("Old"),
    proposed: FieldValue = FieldValue.Text("New"),
    ticked: Boolean = state == FieldState.CHANGES || state == FieldState.FILLS_GAP,
    sources: List<MetadataSource> = listOf(AUDIBLE),
    extraOptions: List<FieldOptionUi> = emptyList(),
    handEdit: HandEdit? = null,
): FieldUi {
    val option = FieldOptionUi("${field.name}-1", proposed, sources)
    return FieldUi(
        field = field,
        state = state,
        current = current,
        options = listOf(option) + extraOptions,
        choice = if (ticked) FieldChoice.Option(option.optionId) else FieldChoice.KeepCurrent,
        proposed = option,
        handEdit = handEdit,
    )
}

@Suppress("LongParameterList")
internal fun ready(
    candidate: CandidateUi = candidate(isBest = true),
    changes: List<FieldUi> = listOf(field(BookField.DESCRIPTION), field(BookField.PUBLISHER)),
    fillsGap: List<FieldUi> =
        listOf(field(BookField.PUBLISH_YEAR, FieldState.FILLS_GAP, current = null, proposed = FieldValue.Year(2021))),
    youEdited: List<FieldUi> =
        listOf(
            field(
                BookField.SUBTITLE,
                FieldState.USER_EDITED,
                handEdit = HandEdit(byUserId = "u-me", byName = "Simon", at = SEP_12),
            ),
        ),
    cover: CoverUi = coverUi(),
    genres: LabelSetUi =
        LabelSetUi(
            yours = listOf(YourLabelUi("Science Fiction", removed = false)),
            suggested = listOf(SuggestionUi("Space Opera", listOf(AUDIBLE, HARDCOVER), selected = true)),
        ),
    moods: LabelSetUi = LabelSetUi(emptyList(), emptyList()),
    chapterNames: ChapterNamesUi = chapters(),
    alreadySame: List<BookField> = listOf(BookField.TITLE, BookField.AUTHORS),
    applyBar: ApplySummary = ApplySummary(fieldCount = 5, coverChanges = true, chapterNameCount = 16),
    applying: Boolean = false,
    applyError: AppError? = null,
): ReviewUiState.Ready =
    ReviewUiState.Ready(
        candidate = candidate,
        summary =
            WhatWillChange(
                coverSource = cover.chosen?.source,
                changeCount = changes.size,
                gapCount = fillsGap.size,
                labelsAdded = genres.added.size + moods.added.size,
                labelsRemoved = genres.removedLabels.size + moods.removedLabels.size,
                chapterNameCount = (chapterNames as? ChapterNamesUi.Available)?.applyCount ?: 0,
                keptEditedCount = youEdited.count { !it.isTicked },
            ),
        cover = cover,
        changes = changes,
        fillsGap = fillsGap,
        youEdited = youEdited,
        genres = genres,
        moods = moods,
        chapterNames = chapterNames,
        alreadySame = alreadySame,
        alreadySameNames = alreadySame.map { it.name },
        lengthAlreadySame = true,
        applyBar = applyBar,
        applying = applying,
        applyError = applyError,
    )

internal fun coverUi(choice: ImageChoice = ImageChoice.Candidate("cv-h")): CoverUi =
    CoverUi(
        current = CurrentCover(hash = "h1", setByHand = false),
        currentCoverPath = null,
        options =
            listOf(
                CoverCandidate("cv-h", HARDCOVER, "https://img.test/h.jpg", 1400, 1400),
                CoverCandidate("cv-a", AUDIBLE, "https://img.test/a.jpg", 500, 500),
            ),
        choice = choice,
    )

internal fun chapters(
    rows: Int = 16,
    included: Boolean = true,
): ChapterNamesUi.Available =
    ChapterNamesUi.Available(
        source = AUDIBLE,
        rows = (0 until rows).map { ChapterRowUi(it, "Chapter ${it + 1}", "Name ${it + 1}", selected = true) },
        unchangedCount = 20,
        included = included,
    )

internal fun receipt(undoable: Boolean = true): MatchReceiptUi =
    MatchReceiptUi(
        receiptId = "r-1",
        fieldCount = 5,
        coverSource = HARDCOVER,
        chapterNameCount = 16,
        changes =
            listOf(
                AppliedChange.Field(BookField.DESCRIPTION, AUDIBLE),
                AppliedChange.Cover(HARDCOVER),
                AppliedChange.Genres(added = listOf("Space Opera"), removed = emptyList()),
                AppliedChange.ChapterNames(16, AUDIBLE),
            ),
        undoable = undoable,
    )

/** 12 Sep 2026, midday UTC — the same calendar day in every time zone the runner might use. */
internal const val SEP_12: Long = 1_789_214_400_000
