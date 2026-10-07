package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.ChapterNamesUi
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.client.presentation.match.WhatWillChange
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_back_to_results
import listenup.composeapp.generated.resources.match_best_match
import listenup.composeapp.generated.resources.match_found_in
import listenup.composeapp.generated.resources.match_none_chosen
import listenup.composeapp.generated.resources.match_retry
import listenup.composeapp.generated.resources.match_review
import listenup.composeapp.generated.resources.match_review_failed_title
import listenup.composeapp.generated.resources.match_review_loading
import listenup.composeapp.generated.resources.match_subtitle_review
import listenup.composeapp.generated.resources.match_sum_change_one
import listenup.composeapp.generated.resources.match_sum_changes
import listenup.composeapp.generated.resources.match_sum_chapter_names
import listenup.composeapp.generated.resources.match_sum_cover
import listenup.composeapp.generated.resources.match_sum_from
import listenup.composeapp.generated.resources.match_sum_gap_one
import listenup.composeapp.generated.resources.match_sum_gaps
import listenup.composeapp.generated.resources.match_sum_kept
import listenup.composeapp.generated.resources.match_sum_labels
import listenup.composeapp.generated.resources.match_title
import listenup.composeapp.generated.resources.match_what_will_change
import org.jetbrains.compose.resources.stringResource

/** Test tag of the Review pane. */
internal const val REVIEW_PANE_TAG = "match-review-pane"

/** Review's sections, in the canvas's order. Each is one list item, so the summary can jump to it. */
internal enum class ReviewSection {
    COVER,
    CHANGES,
    FILLS_GAP,
    YOU_EDITED,
    LABELS,
    CHAPTER_NAMES,
    ALREADY_SAME,
}

/** The sections [ready] renders; a section with nothing in it isn't one. */
internal fun ReviewUiState.Ready.sections(): List<ReviewSection> =
    buildList {
        if (cover.options.isNotEmpty()) add(ReviewSection.COVER)
        if (changes.isNotEmpty()) add(ReviewSection.CHANGES)
        if (fillsGap.isNotEmpty()) add(ReviewSection.FILLS_GAP)
        if (youEdited.isNotEmpty()) add(ReviewSection.YOU_EDITED)
        if (genres.suggested.isNotEmpty() || moods.suggested.isNotEmpty() || genres.changes || moods.changes) {
            add(ReviewSection.LABELS)
        }
        if (chapterNames !is ChapterNamesUi.Hidden) add(ReviewSection.CHAPTER_NAMES)
        if (alreadySame.isNotEmpty() || lengthAlreadySame) add(ReviewSection.ALREADY_SAME)
    }

/** List items before the first section: the header and the What will change summary. */
private const val ITEMS_BEFORE_SECTIONS = 2

/**
 * The Review step: what Apply would do, section by section, with the Apply bar always on screen. On one pane
 * it has its own back to the results; beside the results it is simply "Review".
 */
@Composable
internal fun ReviewPane(
    state: ReviewUiState,
    bookId: String,
    bookTitle: String?,
    viewerId: String?,
    isTwoPane: Boolean,
    actions: BookMatchActions,
    modifier: Modifier = Modifier,
) {
    ListenUpScaffold(
        modifier = modifier.testTag(REVIEW_PANE_TAG),
        topBar = {
            if (isTwoPane) {
                ListenUpTopAppBar(title = stringResource(Res.string.match_review))
            } else {
                ListenUpTopAppBar(
                    title = stringResource(Res.string.match_title),
                    subtitle = bookTitle?.let { stringResource(Res.string.match_subtitle_review, it) },
                    onBack = actions::backToResults,
                    navigationContentDescription = stringResource(Res.string.match_back_to_results),
                )
            }
        },
        bottomBar = {
            if (state is ReviewUiState.Ready) {
                ApplyArea(
                    summary = applySummaryText(state.applyBar),
                    canApply = state.applyBar.canApply,
                    applying = state.applying,
                    applyError = state.applyError,
                    onApply = actions::apply,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                ReviewUiState.NoneChosen -> CenteredMessage(stringResource(Res.string.match_none_chosen))
                is ReviewUiState.Loading -> ReviewLoading()
                is ReviewUiState.Failed -> ReviewFailed(state.error, onRetry = { actions.pick(state.candidate.key) })
                is ReviewUiState.Ready -> ReviewContent(state, bookId, viewerId, actions)
            }
        }
    }
}

@Composable
private fun ReviewContent(
    ready: ReviewUiState.Ready,
    bookId: String,
    viewerId: String?,
    actions: BookMatchActions,
) {
    val listState = rememberLazyListState()
    val sections = ready.sections()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screenMargin, end = Spacing.screenMargin, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        item(key = "header") { ReviewHeader(ready.candidate) }
        item(key = "summary") { WhatWillChangeSummary(ready.summary, sections, listState) }
        sections.forEach { section ->
            item(key = section.name) {
                when (section) {
                    ReviewSection.COVER -> CoverSection(bookId, ready.cover, actions::chooseCover)
                    ReviewSection.CHANGES -> FieldSection(FieldGroup.CHANGES, ready.changes, viewerId, actions)
                    ReviewSection.FILLS_GAP -> FieldSection(FieldGroup.FILLS_GAP, ready.fillsGap, viewerId, actions)
                    ReviewSection.YOU_EDITED -> FieldSection(FieldGroup.YOU_EDITED, ready.youEdited, viewerId, actions)
                    ReviewSection.LABELS -> LabelsSection(ready.genres, ready.moods, ready.summary, actions)
                    ReviewSection.CHAPTER_NAMES -> ChapterNamesSection(ready.chapterNames, actions)
                    ReviewSection.ALREADY_SAME -> AlreadySameSection(ready.alreadySame, ready.lengthAlreadySame)
                }
            }
        }
    }
}

/** The candidate's cover, Best match, title and "Found in A, B and C". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReviewHeader(candidate: CandidateUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteCover(url = candidate.coverUrl, contentDescription = null, modifier = Modifier.size(96.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (candidate.isBest) {
                TonalLabel(
                    label = stringResource(Res.string.match_best_match),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = Icons.Outlined.StarOutline,
                )
            }
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(Res.string.match_found_in, sourcesPhrase(candidate.foundIn.map { it.source })),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One count in the summary and the section it jumps to. */
private data class SummaryItem(
    val count: String,
    val label: String,
    val section: ReviewSection,
)

/** "What will change": each count jumps to its section. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatWillChangeSummary(
    summary: WhatWillChange,
    sections: List<ReviewSection>,
    listState: LazyListState,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val items =
        buildList {
            summary.coverSource?.let {
                add(
                    SummaryItem(
                        stringResource(Res.string.match_sum_cover),
                        stringResource(Res.string.match_sum_from, it.label),
                        ReviewSection.COVER,
                    ),
                )
            }
            if (summary.changeCount > 0) {
                val label =
                    if (summary.changeCount ==
                        1
                    ) {
                        Res.string.match_sum_change_one
                    } else {
                        Res.string.match_sum_changes
                    }
                add(SummaryItem("${summary.changeCount}", stringResource(label), ReviewSection.CHANGES))
            }
            if (summary.gapCount > 0) {
                val label = if (summary.gapCount == 1) Res.string.match_sum_gap_one else Res.string.match_sum_gaps
                add(SummaryItem("${summary.gapCount}", stringResource(label), ReviewSection.FILLS_GAP))
            }
            if (summary.labelsAdded > 0 || summary.labelsRemoved > 0) {
                val count =
                    listOfNotNull(
                        summary.labelsAdded.takeIf { it > 0 }?.let { "+$it" },
                        summary.labelsRemoved.takeIf { it > 0 }?.let { "−$it" },
                    ).joinToString(" ")
                add(SummaryItem(count, stringResource(Res.string.match_sum_labels), ReviewSection.LABELS))
            }
            if (summary.chapterNameCount > 0) {
                add(
                    SummaryItem(
                        "${summary.chapterNameCount}",
                        stringResource(Res.string.match_sum_chapter_names),
                        ReviewSection.CHAPTER_NAMES,
                    ),
                )
            }
            if (summary.keptEditedCount > 0) {
                add(
                    SummaryItem(
                        "${summary.keptEditedCount}",
                        stringResource(Res.string.match_sum_kept),
                        ReviewSection.YOU_EDITED,
                    ),
                )
            }
        }.filter { it.section in sections }
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(Res.string.match_what_will_change),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items.forEach { item ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                haptics.press()
                                val index = ITEMS_BEFORE_SECTIONS + sections.indexOf(item.section)
                                scope.launch { listState.animateScrollToItem(index) }
                            },
                ) {
                    Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                        Text(item.count, style = MaterialTheme.typography.titleLarge)
                        Text(item.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReviewLoading() {
    val loading = stringResource(Res.string.match_review_loading)
    Column(
        modifier =
            Modifier.fillMaxSize().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        ListenUpLoadingIndicator()
        Text(loading, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ReviewFailed(
    error: AppError,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.match_review_failed_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(error.localized(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        ListenUpButton(text = stringResource(Res.string.match_retry), onClick = onRetry, fillMaxWidth = false)
    }
}

@Composable
internal fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.screenMargin), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
