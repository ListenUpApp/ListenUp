package com.calypsan.listenup.client.features.match

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
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.ApplySummary
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.ChapterNamesUi
import com.calypsan.listenup.client.presentation.match.ChapterRowUi
import com.calypsan.listenup.client.presentation.match.CoverUi
import com.calypsan.listenup.client.presentation.match.FieldOptionUi
import com.calypsan.listenup.client.presentation.match.FieldUi
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.LabelKind
import com.calypsan.listenup.client.presentation.match.LabelSetUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.PartialFailure
import com.calypsan.listenup.client.presentation.match.RegionUi
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.client.presentation.match.SuggestionUi
import com.calypsan.listenup.client.presentation.match.WhatWillChange
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import com.calypsan.listenup.client.presentation.match.YourLabelUi

/** Fixed Match details state for content tests. Source labels are invented: the UI treats them as opaque. */
internal object MatchFixtures {
    val ATLAS = MetadataSource(id = "atlas", label = "Atlas")
    val BEACON = MetadataSource(id = "beacon", label = "Beacon")
    val COMPASS = MetadataSource(id = "compass", label = "Compass")

    const val BOOK_ID = "book-1"
    const val VIEWER_ID = "user-1"

    val yourCopy =
        YourCopyUi(
            title = "Project Hail Mary",
            authors = listOf("Andy Weir"),
            coverPath = null,
            coverHash = null,
            durationMs = (16 * 60 + 10) * 60_000L,
            narrators = listOf("Ray Porter"),
            chapterCount = 36,
            year = null,
            isAbridged = false,
        )

    private fun key(id: String) = BookCandidateKey(refs = listOf(ExternalRef(provider = "atlas", id = id)))

    val best =
        CandidateUi(
            id = "atlas:best",
            key = key("best"),
            title = "Project Hail Mary",
            subtitle = null,
            authors = listOf("Andy Weir"),
            narrators = listOf("Ray Porter"),
            durationMs = (16 * 60 + 10) * 60_000L,
            year = 2021,
            format = EditionFormat.UNABRIDGED,
            chapterCount = 36,
            coverUrl = null,
            foundIn = listOf(FoundIn(ATLAS, "us"), FoundIn(BEACON), FoundIn(COMPASS)),
            tier = MatchTier.STRONG,
            isBest = true,
            isCurrentLink = true,
            reasons = listOf(MatchReason.SameNarrator, MatchReason.SameLength, MatchReason.SameChapterCount(36)),
        )

    val maybe =
        best.copy(
            id = "atlas:maybe",
            key = key("maybe"),
            title = "Project Hail Mary (Abridged)",
            narrators = emptyList(),
            durationMs = null,
            chapterCount = null,
            format = EditionFormat.ABRIDGED,
            foundIn = listOf(FoundIn(BEACON)),
            tier = MatchTier.MAYBE,
            isBest = false,
            isCurrentLink = false,
            reasons = listOf(MatchReason.DifferentNarrators),
        )

    val region =
        RegionUi(
            source = ATLAS,
            region = MetadataLocale(region = "us"),
            origin = RegionOrigin.LIBRARY,
            choices = listOf(MetadataLocale(region = "us"), MetadataLocale(region = "uk")),
        )

    val results =
        FindUiState.Results(
            yourCopy = yourCopy,
            steps = listOf(SearchStep.ExistingLink(ATLAS), SearchStep.TitleAuthorLength),
            query = "Project Hail Mary",
            strong = listOf(best),
            maybe = listOf(maybe),
            partialFailure = null,
            region = region,
            pickedKey = null,
        )

    private fun field(
        field: BookField,
        state: FieldState,
        current: FieldValue?,
        proposed: FieldValue,
        ticked: Boolean,
        handEdit: HandEdit? = null,
        sources: List<MetadataSource> = listOf(ATLAS),
    ): FieldUi {
        val option = FieldOptionUi(optionId = "${field.name}-1", value = proposed, sources = sources)
        return FieldUi(
            field = field,
            state = state,
            current = current,
            options = listOf(option),
            choice = if (ticked) FieldChoice.Option(option.optionId) else FieldChoice.KeepCurrent,
            proposed = option,
            handEdit = handEdit,
        )
    }

    val description =
        field(
            BookField.DESCRIPTION,
            FieldState.CHANGES,
            current = FieldValue.Text("A lone astronaut."),
            proposed = FieldValue.Text("<p>Ryland Grace is the sole survivor.</p>"),
            ticked = true,
        )

    val publisher =
        field(
            BookField.PUBLISHER,
            FieldState.FILLS_GAP,
            current = null,
            proposed = FieldValue.Text("Audio Studios"),
            ticked = true,
        )

    val handEditedTitle =
        field(
            BookField.SUBTITLE,
            FieldState.USER_EDITED,
            current = FieldValue.Text("My own subtitle"),
            proposed = FieldValue.Text("A Novel"),
            ticked = false,
            handEdit = HandEdit(byUserId = VIEWER_ID, byName = "Simon", at = null),
        )

    val chapterNames =
        ChapterNamesUi.Available(
            source = ATLAS,
            rows = (1..16).map { ChapterRowUi(ordinal = it, yours = "Chapter $it", theirs = "Named $it", selected = true) },
            unchangedCount = 20,
            included = true,
        )

    val ready =
        ReviewUiState.Ready(
            candidate = best,
            summary =
                WhatWillChange(
                    coverSource = BEACON,
                    changeCount = 1,
                    gapCount = 1,
                    labelsAdded = 1,
                    labelsRemoved = 0,
                    chapterNameCount = 16,
                    keptEditedCount = 1,
                ),
            cover =
                CoverUi(
                    current = CurrentCover(hash = null, setByHand = false),
                    currentCoverPath = null,
                    options = listOf(CoverCandidate(optionId = "cover-1", source = BEACON, url = "", width = 1000, height = 1000)),
                    choice = ImageChoice.Candidate("cover-1"),
                ),
            changes = listOf(description),
            fillsGap = listOf(publisher),
            youEdited = listOf(handEditedTitle),
            genres =
                LabelSetUi(
                    yours = listOf(YourLabelUi("Science Fiction", removed = false)),
                    suggested = listOf(SuggestionUi("Space Opera", listOf(BEACON), selected = true)),
                ),
            moods = LabelSetUi(yours = emptyList(), suggested = emptyList()),
            chapterNames = chapterNames,
            alreadySame = listOf(BookField.TITLE, BookField.AUTHORS),
            lengthAlreadySame = true,
            applyBar = ApplySummary(fieldCount = 2, coverChanges = true, chapterNameCount = 16),
            applying = false,
            applyError = null,
        )

    val receipt =
        MatchReceiptUi(
            receiptId = "r1",
            fieldCount = 5,
            coverSource = BEACON,
            chapterNameCount = 16,
            changes =
                listOf(
                    AppliedChange.Field(BookField.DESCRIPTION, ATLAS),
                    AppliedChange.Cover(BEACON),
                    AppliedChange.Genres(added = listOf("Space Opera"), removed = emptyList()),
                    AppliedChange.ChapterNames(count = 16, source = ATLAS),
                ),
            undoable = true,
        )

    val partial = PartialFailure(failed = listOf(COMPASS), answered = listOf(ATLAS, BEACON))
}

/** Records every action the screen asks for, so a content test can assert what a tap did. */
internal class RecordingMatchActions : BookMatchActions {
    val calls = mutableListOf<String>()
    val twoPaneReports = mutableListOf<Boolean>()

    override fun search(query: String) {
        calls += "search:$query"
    }

    override fun searchByTitle() {
        calls += "searchByTitle"
    }

    override fun chooseStore(region: MetadataLocale) {
        calls += "chooseStore:${region.region}"
    }

    override fun retry() {
        calls += "retry"
    }

    override fun pick(key: BookCandidateKey) {
        calls += "pick:${key.refs.single().id}"
    }

    override fun backToResults() {
        calls += "backToResults"
    }

    override fun useTwoPane(enabled: Boolean) {
        twoPaneReports += enabled
    }

    override fun setFieldTicked(
        field: BookField,
        ticked: Boolean,
    ) {
        calls += "tick:${field.name}:$ticked"
    }

    override fun chooseSource(
        field: BookField,
        choice: FieldChoice,
    ) {
        calls += "source:${field.name}:$choice"
    }

    override fun chooseCover(choice: ImageChoice) {
        calls += "cover:$choice"
    }

    override fun removeYourLabel(
        kind: LabelKind,
        label: String,
    ) {
        calls += "remove:$kind:$label"
    }

    override fun restoreYourLabel(
        kind: LabelKind,
        label: String,
    ) {
        calls += "restore:$kind:$label"
    }

    override fun toggleSuggestion(
        kind: LabelKind,
        label: String,
    ) {
        calls += "suggest:$kind:$label"
    }

    override fun setChapterNamesIncluded(included: Boolean) {
        calls += "chapters:$included"
    }

    override fun toggleChapter(ordinal: Int) {
        calls += "chapter:$ordinal"
    }

    override fun apply() {
        calls += "apply"
    }
}
