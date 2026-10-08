package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import com.calypsan.listenup.client.presentation.match.BiographyUi
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import com.calypsan.listenup.client.presentation.match.PersonHeaderUi
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.client.presentation.match.PhotoUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_apply_biography_a11y
import listenup.composeapp.generated.resources.match_back_to_results
import listenup.composeapp.generated.resources.match_biography_already_same
import listenup.composeapp.generated.resources.match_empty_value
import listenup.composeapp.generated.resources.match_from_source
import listenup.composeapp.generated.resources.match_keep_current
import listenup.composeapp.generated.resources.match_keep_current_photo
import listenup.composeapp.generated.resources.match_keep_yours
import listenup.composeapp.generated.resources.match_maybe
import listenup.composeapp.generated.resources.match_none_chosen
import listenup.composeapp.generated.resources.match_person_header_from
import listenup.composeapp.generated.resources.match_photo_and_biography_separately
import listenup.composeapp.generated.resources.match_photo_from_a11y
import listenup.composeapp.generated.resources.match_photo_set_by_hand
import listenup.composeapp.generated.resources.match_proposed
import listenup.composeapp.generated.resources.match_proposed_from
import listenup.composeapp.generated.resources.match_review
import listenup.composeapp.generated.resources.match_section_biography
import listenup.composeapp.generated.resources.match_section_changes
import listenup.composeapp.generated.resources.match_section_fills_gap
import listenup.composeapp.generated.resources.match_section_photo
import listenup.composeapp.generated.resources.match_source_switch_a11y
import listenup.composeapp.generated.resources.match_strong_match
import listenup.composeapp.generated.resources.match_person_header_found_in
import listenup.composeapp.generated.resources.match_subtitle_review
import listenup.composeapp.generated.resources.match_title
import listenup.composeapp.generated.resources.match_what_will_change
import listenup.composeapp.generated.resources.match_you_edited_flag
import listenup.composeapp.generated.resources.match_your_photo
import listenup.composeapp.generated.resources.match_yours_no_photo
import org.jetbrains.compose.resources.stringResource

/** A photo tile's picture: the canvas's 96dp circle. */
private val PHOTO_TILE = 96.dp

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
                is PersonReviewUiState.Ready -> PersonReviewContent(state, contributorId, header, viewerId, actions)
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
            candidate.libraryLine()?.let {
                Text(
                    text = it,
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

/** Photo: Keep current (their real photo, or initials) or one source's photo, as one radio group. */
@Composable
private fun PhotoSection(
    photo: PhotoUi,
    contributorId: String,
    name: String,
    candidateName: String,
    onChoose: (ImageChoice) -> Unit,
) {
    SectionGroup(label = stringResource(Res.string.match_section_photo)) {
        SectionSegment {
            Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (photo.state == FieldState.USER_EDITED) {
                    YouEditedFlag()
                    Text(
                        text = stringResource(Res.string.match_photo_set_by_hand),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    PhotoTile(
                        selected = photo.choice is ImageChoice.KeepCurrent,
                        label = stringResource(Res.string.match_keep_current),
                        detail =
                            stringResource(
                                if (photo.currentPath != null) Res.string.match_your_photo else Res.string.match_yours_no_photo,
                            ),
                        accessibleName = stringResource(Res.string.match_keep_current_photo),
                        onSelect = { onChoose(ImageChoice.KeepCurrent) },
                    ) {
                        CurrentPersonPhoto(contributorId = contributorId, name = name, imagePath = photo.currentPath, size = PHOTO_TILE)
                    }
                    photo.options.forEach { option ->
                        PhotoTile(
                            selected = (photo.choice as? ImageChoice.Candidate)?.optionId == option.optionId,
                            label = option.source.label,
                            detail = stringResource(Res.string.match_proposed).takeIf { option == photo.proposed },
                            accessibleName = stringResource(Res.string.match_photo_from_a11y, option.source.label),
                            onSelect = { onChoose(ImageChoice.Candidate(option.optionId)) },
                        ) { PersonPhoto(name = candidateName, url = option.url, size = PHOTO_TILE) }
                    }
                }
            }
        }
    }
}

/** One choice of photo: the picture, a radio and its label, and what it is — a radio named for what it keeps. */
@Composable
private fun PhotoTile(
    selected: Boolean,
    label: String,
    detail: String?,
    accessibleName: String,
    onSelect: () -> Unit,
    image: @Composable () -> Unit,
) {
    val haptics = LocalHaptics.current
    Column(
        modifier =
            Modifier
                .widthIn(min = PHOTO_TILE + Spacing.md * 2)
                .selectedOutline(selected)
                .selectable(selected = selected, role = Role.RadioButton) {
                    if (!selected) haptics.toggle(on = true)
                    onSelect()
                }.semantics { contentDescription = accessibleName }
                .padding(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        image()
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = Spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Modifier.selectedOutline(selected: Boolean): Modifier =
    if (selected) border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else this

/**
 * Biography: the tick (Apply writes it), what kind of change it is and its source, the source switch when there
 * is more than one way to go, then Yours → Proposed. Hand-edited biographies start unticked and say who edited
 * them; one that already matches says so and has nothing to tick.
 */
@Composable
private fun BiographySection(
    biography: BiographyUi,
    viewerId: String?,
    actions: PersonMatchActions,
) {
    val sectionName = stringResource(Res.string.match_section_biography)
    SectionGroup(label = sectionName) {
        SectionSegment {
            Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (biography.state == FieldState.SAME) {
                    Text(
                        text = stringResource(Res.string.match_biography_already_same),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                val source = sourcesPhrase(biography.proposed.sources)
                BiographyTick(biography = biography, source = source, onTicked = actions::setBiographyTicked)
                BiographySourceSwitch(biography = biography, sectionName = sectionName, onChoose = actions::chooseBiographySource)
                YoursAndProposed(
                    yours = biography.current?.asPlainText() ?: stringResource(Res.string.match_empty_value),
                    proposed = biography.proposed.value.displayText(),
                    proposedLabel =
                        if (biography.state == FieldState.USER_EDITED) {
                            stringResource(Res.string.match_proposed_from, source)
                        } else {
                            stringResource(Res.string.match_proposed)
                        },
                    ticked = biography.isTicked,
                    alwaysStacked = true,
                )
                biography.handEdit?.let { edit ->
                    Text(
                        text = editedNote(edit, viewerId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The biography's checkbox row: "Fills a gap · from Beacon", or the You edited this flag. */
@Composable
private fun BiographyTick(
    biography: BiographyUi,
    source: String,
    onTicked: (Boolean) -> Unit,
) {
    val haptics = LocalHaptics.current
    val edited = biography.state == FieldState.USER_EDITED
    val stateLabel =
        stringResource(
            when (biography.state) {
                FieldState.FILLS_GAP -> Res.string.match_section_fills_gap
                FieldState.USER_EDITED -> Res.string.match_you_edited_flag
                else -> Res.string.match_section_changes
            },
        )
    val fromSource = stringResource(Res.string.match_from_source, source)
    val accessibleName = "${stringResource(Res.string.match_apply_biography_a11y)}. $stateLabel, $fromSource"
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = biography.isTicked, role = Role.Checkbox) { ticked ->
                    haptics.toggle(on = ticked)
                    onTicked(ticked)
                }.semantics { contentDescription = accessibleName },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveCheckbox(checked = biography.isTicked)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (edited) YouEditedFlag() else Text(stateLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                text = fromSource,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Beacon | Atlas | Keep yours" — shown only when there is more than one way to go. */
@Composable
private fun BiographySourceSwitch(
    biography: BiographyUi,
    sectionName: String,
    onChoose: (FieldChoice) -> Unit,
) {
    val haptics = LocalHaptics.current
    val choices =
        biography.options.map { option ->
            ButtonGroupChoice<FieldChoice>(
                value = FieldChoice.Option(option.optionId),
                label = option.sources.first().label,
                accessibleLabel = sourcesPhrase(option.sources),
            )
        } +
            listOfNotNull(
                ButtonGroupChoice<FieldChoice>(FieldChoice.KeepCurrent, stringResource(Res.string.match_keep_yours))
                    .takeIf { biography.canKeepYours },
            )
    if (choices.size < 2) return
    ConnectedSelectButtonGroup(
        choices = choices,
        selected = biography.choice,
        onSelect = { choice ->
            haptics.toggle(on = choice is FieldChoice.Option)
            onChoose(choice)
        },
        groupLabel = stringResource(Res.string.match_source_switch_a11y, sectionName),
    )
}

/** "You edited this" in the tertiary container, with its glyph, so it never rests on colour alone. */
@Composable
private fun YouEditedFlag() {
    TonalLabel(
        label = stringResource(Res.string.match_you_edited_flag),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = Icons.Outlined.Edit,
    )
}
