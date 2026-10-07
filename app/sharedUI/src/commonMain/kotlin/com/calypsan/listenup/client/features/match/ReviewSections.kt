package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.ChapterNamesUi
import com.calypsan.listenup.client.presentation.match.ChapterRowUi
import com.calypsan.listenup.client.presentation.match.CoverUi
import com.calypsan.listenup.client.presentation.match.LabelKind
import com.calypsan.listenup.client.presentation.match.LabelSetUi
import com.calypsan.listenup.client.presentation.match.WhatWillChange
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_already_same_one
import listenup.composeapp.generated.resources.match_already_same_summary
import listenup.composeapp.generated.resources.match_apply_chapter_names
import listenup.composeapp.generated.resources.match_chapter_count_mismatch
import listenup.composeapp.generated.resources.match_chapter_names_from
import listenup.composeapp.generated.resources.match_chapter_row_a11y
import listenup.composeapp.generated.resources.match_cover_dimensions
import listenup.composeapp.generated.resources.match_cover_from_a11y
import listenup.composeapp.generated.resources.match_field_length
import listenup.composeapp.generated.resources.match_from_source
import listenup.composeapp.generated.resources.match_genres
import listenup.composeapp.generated.resources.match_keep_current
import listenup.composeapp.generated.resources.match_keep_current_cover
import listenup.composeapp.generated.resources.match_moods
import listenup.composeapp.generated.resources.match_remove_label
import listenup.composeapp.generated.resources.match_restore_label
import listenup.composeapp.generated.resources.match_section_already_same
import listenup.composeapp.generated.resources.match_section_chapter_names
import listenup.composeapp.generated.resources.match_section_cover
import listenup.composeapp.generated.resources.match_section_genres_moods
import listenup.composeapp.generated.resources.match_show_all_chapters
import listenup.composeapp.generated.resources.match_suggested
import listenup.composeapp.generated.resources.match_suggested_from
import listenup.composeapp.generated.resources.match_suggestion_a11y
import listenup.composeapp.generated.resources.match_tags_note
import listenup.composeapp.generated.resources.match_yours_kept
import org.jetbrains.compose.resources.stringResource

/** Chapter rows shown before Show all. */
private const val CHAPTER_ROWS_SHOWN = 3

private val COVER_TILE = 104.dp

/** Cover: Keep current (your actual cover) or one candidate, as one radio group. */
@Composable
internal fun CoverSection(
    bookId: String,
    cover: CoverUi,
    onChoose: (ImageChoice) -> Unit,
) {
    SectionGroup(label = stringResource(Res.string.match_section_cover)) {
        SectionSegment {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup()
                        .padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CoverTile(
                    selected = cover.choice is ImageChoice.KeepCurrent,
                    label = stringResource(Res.string.match_keep_current),
                    detail = null,
                    accessibleName = stringResource(Res.string.match_keep_current_cover),
                    onSelect = { onChoose(ImageChoice.KeepCurrent) },
                ) { modifier ->
                    BookCoverImage(
                        bookId = bookId,
                        coverPath = cover.currentCoverPath,
                        coverHash = cover.current?.hash,
                        contentDescription = null,
                        modifier = modifier,
                    )
                }
                cover.options.forEach { option ->
                    val sized = option.width > 0 && option.height > 0
                    CoverTile(
                        selected = (cover.choice as? ImageChoice.Candidate)?.optionId == option.optionId,
                        label = option.source.label,
                        detail = if (sized) stringResource(Res.string.match_cover_dimensions, option.width, option.height) else null,
                        accessibleName = stringResource(Res.string.match_cover_from_a11y, option.source.label, option.width, option.height),
                        onSelect = { onChoose(ImageChoice.Candidate(option.optionId)) },
                    ) { modifier -> RemoteCover(url = option.url, contentDescription = null, modifier = modifier) }
                }
            }
        }
    }
}

@Composable
private fun CoverTile(
    selected: Boolean,
    label: String,
    detail: String?,
    accessibleName: String,
    onSelect: () -> Unit,
    image: @Composable (Modifier) -> Unit,
) {
    val haptics = LocalHaptics.current
    Column(
        modifier =
            Modifier
                .width(COVER_TILE)
                .selectable(selected = selected, role = Role.RadioButton) {
                    if (!selected) haptics.toggle(on = true)
                    onSelect()
                }.semantics { contentDescription = accessibleName },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box {
            image(
                Modifier
                    .size(COVER_TILE)
                    .then(
                        if (selected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                        } else {
                            Modifier
                        },
                    ),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Genres & moods: yours kept unless you remove one, suggestions you can leave out; tags are never touched. */
@Composable
internal fun LabelsSection(
    genres: LabelSetUi,
    moods: LabelSetUi,
    summary: WhatWillChange,
    actions: BookMatchActions,
) {
    SectionGroup(
        label = stringResource(Res.string.match_section_genres_moods),
        trailing = {
            if (summary.labelsAdded > 0) {
                Text("+${summary.labelsAdded}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        SectionSegment {
            Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                LabelSet(stringResource(Res.string.match_genres), genres, LabelKind.GENRES, actions)
                LabelSet(stringResource(Res.string.match_moods), moods, LabelKind.MOODS, actions)
                Text(
                    text = stringResource(Res.string.match_tags_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelSet(
    title: String,
    set: LabelSetUi,
    kind: LabelKind,
    actions: BookMatchActions,
) {
    if (set.yours.isEmpty() && set.suggested.isEmpty()) return
    val haptics = LocalHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (set.yours.isNotEmpty()) {
            SubLabel(stringResource(Res.string.match_yours_kept))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                set.yours.forEach { label ->
                    val name =
                        if (label.removed) {
                            stringResource(Res.string.match_restore_label, label.label)
                        } else {
                            stringResource(Res.string.match_remove_label, label.label)
                        }
                    InputChip(
                        selected = false,
                        onClick = {
                            haptics.toggle(on = label.removed)
                            if (label.removed) actions.restoreYourLabel(kind, label.label) else actions.removeYourLabel(kind, label.label)
                        },
                        label = {
                            Text(label.label, textDecoration = if (label.removed) TextDecoration.LineThrough else null)
                        },
                        trailingIcon = {
                            Icon(if (label.removed) Icons.Filled.Undo else Icons.Filled.Close, contentDescription = null)
                        },
                        modifier = Modifier.semantics { contentDescription = name },
                    )
                }
            }
        }
        if (set.suggested.isNotEmpty()) {
            val sharedSource = set.suggested.map { it.sources }.distinct().singleOrNull()
            SubLabel(
                sharedSource?.let { stringResource(Res.string.match_suggested_from, sourcesPhrase(it)) }
                    ?: stringResource(Res.string.match_suggested),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                set.suggested.forEach { suggestion ->
                    val sources = sourcesPhrase(suggestion.sources)
                    val accessibleName = stringResource(Res.string.match_suggestion_a11y, suggestion.label, sources)
                    FilterChip(
                        selected = suggestion.selected,
                        onClick = {
                            haptics.toggle(on = !suggestion.selected)
                            actions.toggleSuggestion(kind, suggestion.label)
                        },
                        label = {
                            Text(if (sharedSource == null) suggestion.label + DOT + sources else suggestion.label)
                        },
                        leadingIcon = {
                            Icon(if (suggestion.selected) Icons.Filled.Check else Icons.Filled.Add, contentDescription = null)
                        },
                        modifier = Modifier.semantics { contentDescription = accessibleName },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Chapter names: include them or not, the first rows with Show all; a count mismatch is shown, never applied. */
@Composable
internal fun ChapterNamesSection(
    chapterNames: ChapterNamesUi,
    actions: BookMatchActions,
) {
    when (chapterNames) {
        ChapterNamesUi.Hidden -> {
            Unit
        }

        is ChapterNamesUi.CountMismatch -> {
            SectionGroup(label = stringResource(Res.string.match_section_chapter_names)) {
                SectionSegment {
                    Text(
                        text =
                            stringResource(
                                Res.string.match_chapter_count_mismatch,
                                chapterNames.source.label,
                                chapterNames.theirs,
                                chapterNames.yours,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
            }
        }

        is ChapterNamesUi.Available -> {
            AvailableChapterNames(chapterNames, actions)
        }
    }
}

@Composable
private fun AvailableChapterNames(
    names: ChapterNamesUi.Available,
    actions: BookMatchActions,
) {
    val haptics = LocalHaptics.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    SectionGroup(
        label = stringResource(Res.string.match_section_chapter_names),
        trailing = {
            Text("${names.applyCount}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        },
    ) {
        SectionSegment {
            Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = names.included, role = Role.Checkbox) {
                            haptics.toggle(on = it)
                            actions.setChapterNamesIncluded(it)
                        },
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveCheckbox(checked = names.included)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.match_apply_chapter_names), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(Res.string.match_from_source, names.source.label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text =
                        stringResource(
                            Res.string.match_chapter_names_from,
                            names.rows.size,
                            names.rows.size + names.unchangedCount,
                            names.source.label,
                            names.unchangedCount,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val rows = if (showAll) names.rows else names.rows.take(CHAPTER_ROWS_SHOWN)
                rows.forEach { row -> ChapterRow(row, enabled = names.included, onToggle = { actions.toggleChapter(row.ordinal) }) }
                if (!showAll && names.rows.size > CHAPTER_ROWS_SHOWN) {
                    TextButton(onClick = { showAll = true }) {
                        Text(stringResource(Res.string.match_show_all_chapters, names.rows.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    row: ChapterRowUi,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val accessibleName = stringResource(Res.string.match_chapter_row_a11y, row.ordinal, row.yours, row.theirs)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = row.selected, enabled = enabled, role = Role.Checkbox) {
                    haptics.toggle(on = it)
                    onToggle()
                }.semantics { contentDescription = accessibleName },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveCheckbox(checked = row.selected && enabled)
        Text(
            text = row.yours,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.LineThrough,
            modifier = Modifier.weight(1f),
        )
        Text("→", style = MaterialTheme.typography.bodyMedium)
        Text(row.theirs, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Already the same, collapsed to one line: "6 fields already match: Authors, Narrators, …, Length". */
@Composable
internal fun AlreadySameSection(
    alreadySame: List<BookField>,
    lengthAlreadySame: Boolean,
) {
    val names = alreadySame.map { it.displayName() } + listOfNotNull(stringResource(Res.string.match_field_length).takeIf { lengthAlreadySame })
    SectionGroup(label = stringResource(Res.string.match_section_already_same)) {
        SectionSegment {
            Text(
                text =
                    if (names.size == 1) {
                        stringResource(Res.string.match_already_same_one, names.single())
                    } else {
                        stringResource(Res.string.match_already_same_summary, names.size, names.joinToString(", "))
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.md),
            )
        }
    }
}
