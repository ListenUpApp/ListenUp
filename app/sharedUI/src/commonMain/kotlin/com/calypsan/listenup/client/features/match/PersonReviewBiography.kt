package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.BiographyUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_apply_biography_a11y
import listenup.composeapp.generated.resources.match_biography_already_same
import listenup.composeapp.generated.resources.match_empty_value
import listenup.composeapp.generated.resources.match_from_source
import listenup.composeapp.generated.resources.match_keep_yours
import listenup.composeapp.generated.resources.match_proposed
import listenup.composeapp.generated.resources.match_proposed_from
import listenup.composeapp.generated.resources.match_section_biography
import listenup.composeapp.generated.resources.match_section_changes
import listenup.composeapp.generated.resources.match_section_fills_gap
import listenup.composeapp.generated.resources.match_source_switch_a11y
import listenup.composeapp.generated.resources.match_you_edited_flag
import org.jetbrains.compose.resources.stringResource

/**
 * Biography: the tick (Apply writes it), what kind of change it is and its source, the source switch when there
 * is more than one way to go, then Yours → Proposed. Hand-edited biographies start unticked and say who edited
 * them; one that already matches says so and has nothing to tick.
 */
@Composable
internal fun BiographySection(
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
                FieldState.CHANGES, FieldState.SAME -> Res.string.match_section_changes
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
