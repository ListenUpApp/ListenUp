package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.components.CountBadge
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.FieldUi
import com.calypsan.listenup.client.util.formatDate
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_edited_by_hand
import listenup.composeapp.generated.resources.match_edited_by_hand_undated
import listenup.composeapp.generated.resources.match_edited_by_name
import listenup.composeapp.generated.resources.match_edited_by_name_undated
import listenup.composeapp.generated.resources.match_edited_by_you
import listenup.composeapp.generated.resources.match_edited_by_you_undated
import listenup.composeapp.generated.resources.match_empty_value
import listenup.composeapp.generated.resources.match_field_changes_a11y
import listenup.composeapp.generated.resources.match_field_fills_gap_a11y
import listenup.composeapp.generated.resources.match_field_full_a11y
import listenup.composeapp.generated.resources.match_from_source
import listenup.composeapp.generated.resources.match_keep_yours
import listenup.composeapp.generated.resources.match_proposed
import listenup.composeapp.generated.resources.match_proposed_from
import listenup.composeapp.generated.resources.match_read_all
import listenup.composeapp.generated.resources.match_section_changes
import listenup.composeapp.generated.resources.match_section_fills_gap
import listenup.composeapp.generated.resources.match_section_you_edited
import listenup.composeapp.generated.resources.match_show_less
import listenup.composeapp.generated.resources.match_source_switch_a11y
import listenup.composeapp.generated.resources.match_you_edited_flag
import listenup.composeapp.generated.resources.match_yours
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Lines a long value shows before Read all. */
private const val COLLAPSED_LINES = 3

/** From this font scale, Yours and Proposed stack instead of sitting side by side. */
internal const val LARGE_TEXT_SCALE = 1.5f

/** The three field sections, each with its heading. */
internal enum class FieldGroup(
    val title: StringResource,
) {
    CHANGES(Res.string.match_section_changes),
    FILLS_GAP(Res.string.match_section_fills_gap),
    YOU_EDITED(Res.string.match_section_you_edited),
}

/** Test tag of one field's checkbox row. */
internal fun fieldTag(field: BookField): String = "match-field-${field.name}"

/** A titled group of field rows — Changes, Fills a gap, or You edited this. */
@Composable
internal fun FieldSection(
    group: FieldGroup,
    fields: List<FieldUi>,
    viewerId: String?,
    actions: BookMatchActions,
) {
    SectionGroup(
        label = stringResource(group.title),
        trailing = { CountBadge(count = fields.size) },
    ) {
        fields.forEach { field ->
            SectionSegment { FieldRow(field = field, group = group, viewerId = viewerId, actions = actions) }
        }
    }
}

/**
 * One field: the checkbox (ticked = Apply writes it), the source switch when several sources offer it, then
 * Yours and Proposed. A hand-edited field carries its flag and who edited it, and starts unticked.
 */
@Composable
private fun FieldRow(
    field: FieldUi,
    group: FieldGroup,
    viewerId: String?,
    actions: BookMatchActions,
) {
    val haptics = LocalHaptics.current
    val name = field.field.displayName()
    val source = sourcesPhrase(field.proposed.sources)
    val yours = field.current?.displayText() ?: stringResource(Res.string.match_empty_value)
    val proposed = field.proposed.value.displayText()
    val accessibleName =
        when (group) {
            FieldGroup.CHANGES -> {
                stringResource(Res.string.match_field_changes_a11y, name, source)
            }

            FieldGroup.FILLS_GAP -> {
                stringResource(Res.string.match_field_fills_gap_a11y, name, source)
            }

            FieldGroup.YOU_EDITED -> {
                stringResource(Res.string.match_field_full_a11y, name, yours, source, proposed) + " " +
                    stringResource(Res.string.match_you_edited_flag) + "."
            }
        }
    Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = field.isTicked, role = Role.Checkbox) { ticked ->
                        haptics.toggle(on = ticked)
                        actions.setFieldTicked(field.field, ticked)
                    }.semantics { contentDescription = accessibleName }
                    .testTag(fieldTag(field.field)),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveCheckbox(checked = field.isTicked)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(Res.string.match_from_source, source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (group == FieldGroup.YOU_EDITED) {
            TonalLabel(
                label = stringResource(Res.string.match_you_edited_flag),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = Icons.Outlined.Edit,
            )
        }
        if (field.options.size > 1) SourceSwitch(field = field, fieldName = name, onChoose = actions::chooseSource)
        YoursAndProposed(
            yours = yours,
            proposed = proposed,
            proposedLabel =
                if (group == FieldGroup.YOU_EDITED) {
                    stringResource(Res.string.match_proposed_from, source)
                } else {
                    stringResource(Res.string.match_proposed)
                },
            ticked = field.isTicked,
        )
        field.handEdit?.let { edit ->
            Text(
                text = editedNote(edit, viewerId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Source A | Source B | Keep yours" as one connected group; the choice and the checkbox are one value. */
@Composable
private fun SourceSwitch(
    field: FieldUi,
    fieldName: String,
    onChoose: (BookField, FieldChoice) -> Unit,
) {
    val haptics = LocalHaptics.current
    val choices =
        field.options.map { option ->
            ButtonGroupChoice<FieldChoice>(
                value = FieldChoice.Option(option.optionId),
                label = option.sources.first().label,
                accessibleLabel = sourcesPhrase(option.sources),
            )
        } +
            if (field.canKeepYours) {
                listOf(ButtonGroupChoice<FieldChoice>(FieldChoice.KeepCurrent, stringResource(Res.string.match_keep_yours)))
            } else {
                emptyList()
            }
    ConnectedSelectButtonGroup(
        choices = choices,
        selected = field.choice,
        onSelect = { choice ->
            haptics.toggle(on = choice is FieldChoice.Option)
            onChoose(field.field, choice)
        },
        groupLabel = stringResource(Res.string.match_source_switch_a11y, fieldName),
    )
}

/**
 * Yours → Proposed: side by side, or stacked at large text or when [alwaysStacked] (a biography is prose, so it
 * reads down the page). Long values show three lines and Read all.
 */
@Composable
internal fun YoursAndProposed(
    yours: String,
    proposed: String,
    proposedLabel: String,
    ticked: Boolean,
    alwaysStacked: Boolean = false,
) {
    val stacked = alwaysStacked || LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    val yoursBlock: @Composable (Modifier) -> Unit = { modifier ->
        LabelledValue(stringResource(Res.string.match_yours), yours, emphasised = false, modifier = modifier)
    }
    val proposedBlock: @Composable (Modifier) -> Unit = { modifier ->
        LabelledValue(proposedLabel, proposed, emphasised = ticked, modifier = modifier)
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            yoursBlock(Modifier.fillMaxWidth())
            proposedBlock(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            yoursBlock(Modifier.weight(1f))
            proposedBlock(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabelledValue(
    label: String,
    value: String,
    emphasised: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(value) { mutableStateOf(false) }
    var overflows by rememberSaveable(value) { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.Medium else null,
            color = if (emphasised) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) Res.string.match_show_less else Res.string.match_read_all))
            }
        }
    }
}

/** "Edited by you, 12 Sep. Kept unless you tick it." — by you, by name, or by hand; undated when unknown. */
@Composable
internal fun editedNote(
    edit: HandEdit,
    viewerId: String?,
): String {
    val date = edit.at?.takeIf { it > 0 }?.let { formatDate(it, "d MMM") }
    val byYou = edit.byUserId != null && edit.byUserId == viewerId
    val name = edit.byName
    return when {
        byYou && date != null -> stringResource(Res.string.match_edited_by_you, date)
        byYou -> stringResource(Res.string.match_edited_by_you_undated)
        name != null && date != null -> stringResource(Res.string.match_edited_by_name, name, date)
        name != null -> stringResource(Res.string.match_edited_by_name_undated, name)
        date != null -> stringResource(Res.string.match_edited_by_hand, date)
        else -> stringResource(Res.string.match_edited_by_hand_undated)
    }
}
