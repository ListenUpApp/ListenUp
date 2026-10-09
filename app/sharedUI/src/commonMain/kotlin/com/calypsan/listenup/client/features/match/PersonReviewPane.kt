package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import com.calypsan.listenup.client.presentation.match.PersonHeaderUi
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_back_to_results
import listenup.composeapp.generated.resources.match_maybe
import listenup.composeapp.generated.resources.match_none_chosen
import listenup.composeapp.generated.resources.match_person_header_from
import listenup.composeapp.generated.resources.match_photo_and_biography_separately
import listenup.composeapp.generated.resources.match_review
import listenup.composeapp.generated.resources.match_strong_match
import listenup.composeapp.generated.resources.match_person_header_found_in
import listenup.composeapp.generated.resources.match_subtitle_review
import listenup.composeapp.generated.resources.match_title
import listenup.composeapp.generated.resources.match_what_will_change
import listenup.composeapp.generated.resources.match_you_edited_flag
import org.jetbrains.compose.resources.stringResource

/** The person's photo in the Review header. */
private val HEADER_PHOTO = 112.dp

/**
 * The Review step for a person: the candidate, What will change, then the photo and the biography — each its own
 * decision — with the Apply bar always on screen. On one pane it has its own back to the results; beside the
 * results it is simply "Review".
 */
@Composable
internal fun PersonReviewPane(
    state: PersonReviewUiState,
    contributorId: String,
    header: PersonHeaderUi?,
    viewerId: String?,
    isTwoPane: Boolean,
    actions: PersonMatchActions,
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
                    subtitle = header?.let { stringResource(Res.string.match_subtitle_review, it.name) },
                    onBack = actions::backToResults,
                    navigationContentDescription = stringResource(Res.string.match_back_to_results),
                )
            }
        },
        bottomBar = {
            if (state is PersonReviewUiState.Ready) {
                ApplyArea(
                    summary = personApplySummaryText(state.applyBar),
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
                PersonReviewUiState.NoneChosen -> CenteredMessage(stringResource(Res.string.match_none_chosen))
                is PersonReviewUiState.Loading -> ReviewLoading()
                is PersonReviewUiState.Failed -> ReviewFailed(state.error, onRetry = { actions.pick(state.candidate.key) })
                is PersonReviewUiState.Ready ->
                    PersonReviewContent(
                        ready = state,
                        contributorId = contributorId,
                        header = header,
                        viewerId = viewerId,
                        actions = actions,
                    )
            }
        }
    }
}

@Composable
private fun PersonReviewContent(
    ready: PersonReviewUiState.Ready,
    contributorId: String,
    header: PersonHeaderUi?,
    viewerId: String?,
    actions: PersonMatchActions,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screenMargin, end = Spacing.screenMargin, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        item(key = "header") { PersonReviewHeader(ready.candidate) }
        item(key = "summary") { WhatWillChangeNote() }
        ready.photo?.let { photo ->
            item(key = "photo") {
                PhotoSection(
                    photo = photo,
                    contributorId = contributorId,
                    name = header?.name ?: ready.candidate.name,
                    candidateName = ready.candidate.name,
                    onChoose = actions::choosePhoto,
                )
            }
        }
        ready.biography?.let { biography ->
            item(key = "biography") { BiographySection(biography, viewerId, actions) }
        }
    }
}

/** The candidate's photo, Strong match or Maybe, their name, "Narrator · from Beacon" and their books here. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PersonReviewHeader(candidate: PersonCandidateUi) {
    val strong = candidate.tier == MatchTier.STRONG
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonPhoto(name = candidate.name, url = candidate.photoUrl, size = HEADER_PHOTO)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TonalLabel(
                label = stringResource(if (strong) Res.string.match_strong_match else Res.string.match_maybe),
                containerColor =
                    if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor =
                    if (strong) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                icon = if (strong) Icons.Filled.CheckCircle else null,
            )
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text =
                    candidate.shownRole?.let {
                        stringResource(Res.string.match_person_header_from, roleName(it), sourcesPhrase(candidate.foundIn))
                    } ?: stringResource(Res.string.match_person_header_found_in, sourcesPhrase(candidate.foundIn)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidate.libraryLine()?.let { libraryLine ->
                Text(
                    text = libraryLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (candidate.libraryCount > 0) {
                            MaterialTheme.extendedColors.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

/** "What will change: Photo and biography, chosen separately." */
@Composable
private fun WhatWillChangeNote() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = stringResource(Res.string.match_what_will_change),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(Res.string.match_photo_and_biography_separately),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "You edited this" in the tertiary container, with its glyph, so it never rests on colour alone. */
@Composable
internal fun YouEditedFlag() {
    TonalLabel(
        label = stringResource(Res.string.match_you_edited_flag),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = Icons.Outlined.Edit,
    )
}
