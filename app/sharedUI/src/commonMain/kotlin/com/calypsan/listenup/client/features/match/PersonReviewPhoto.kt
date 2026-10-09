package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.PhotoUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_keep_current
import listenup.composeapp.generated.resources.match_keep_current_photo
import listenup.composeapp.generated.resources.match_photo_from_a11y
import listenup.composeapp.generated.resources.match_photo_set_by_hand
import listenup.composeapp.generated.resources.match_proposed
import listenup.composeapp.generated.resources.match_section_photo
import listenup.composeapp.generated.resources.match_your_photo
import listenup.composeapp.generated.resources.match_yours_no_photo
import org.jetbrains.compose.resources.stringResource

/** A photo tile's picture: the canvas's 96dp circle. */
private val PHOTO_TILE = 96.dp

/** Photo: Keep current (their real photo, or initials) or one source's photo, as one radio group. */
@Composable
internal fun PhotoSection(
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
                val currentPhotoLabel =
                    if (photo.currentPath != null) Res.string.match_your_photo else Res.string.match_yours_no_photo
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    PhotoTile(
                        selected = photo.choice is ImageChoice.KeepCurrent,
                        label = stringResource(Res.string.match_keep_current),
                        detail = stringResource(currentPhotoLabel),
                        accessibleName = stringResource(Res.string.match_keep_current_photo),
                        onSelect = { onChoose(ImageChoice.KeepCurrent) },
                    ) {
                        CurrentPersonPhoto(
                            contributorId = contributorId,
                            name = name,
                            imagePath = photo.currentPath,
                            size = PHOTO_TILE,
                        )
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
        detail?.let { detailText ->
            Text(
                text = detailText,
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
