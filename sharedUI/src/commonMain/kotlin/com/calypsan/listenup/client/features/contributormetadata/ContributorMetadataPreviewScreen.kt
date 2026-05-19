@file:Suppress("LongMethod", "CognitiveComplexMethod", "StringLiteralDuplication")

package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataField
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.contributor_apply_selectedcount_of_availablefieldcount
import listenup.composeapp.generated.resources.contributor_audible
import listenup.composeapp.generated.resources.contributor_change_match
import listenup.composeapp.generated.resources.contributor_current
import listenup.composeapp.generated.resources.contributor_current_image
import listenup.composeapp.generated.resources.contributor_failed_to_load_profile
import listenup.composeapp.generated.resources.common_image
import listenup.composeapp.generated.resources.contributor_new_image
import listenup.composeapp.generated.resources.contributor_no_change
import listenup.composeapp.generated.resources.contributor_no_image_available_from_audible
import listenup.composeapp.generated.resources.contributor_preview_changes

/**
 * Full-screen preview of contributor metadata changes before applying.
 *
 * Shows current vs. Audible data with checkboxes for each field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorMetadataPreviewScreen(
    state: ContributorMetadataUiState,
    onToggleField: (ContributorMetadataField) -> Unit,
    onApply: () -> Unit,
    onChangeMatch: () -> Unit,
    onBack: () -> Unit,
) {
    val currentContributor = state.currentContributor
    val profile = state.previewProfile
    val selections = state.selections

    // Image is only available if Audible provides one
    val hasImage = !profile?.imageUrl.isNullOrBlank()
    val availableFieldCount = if (hasImage) 3 else 2

    val hasAnySelected = selections.name || selections.biography || (hasImage && selections.image)
    val selectedCount =
        listOfNotNull(
            if (selections.name) true else null,
            if (selections.biography) true else null,
            if (hasImage && selections.image) true else null,
        ).size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.contributor_preview_changes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    state.applyError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onChangeMatch,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(Res.string.contributor_change_match))
                        }

                        Button(
                            onClick = onApply,
                            enabled = !state.isApplying && hasAnySelected,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.isApplying) {
                                ListenUpLoadingIndicatorSmall(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(
                                    stringResource(
                                        Res.string.contributor_apply_selectedcount_of_availablefieldcount,
                                        selectedCount,
                                        availableFieldCount,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.isLoadingPreview -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    ListenUpLoadingIndicator()
                }
            }

            state.previewError != null -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.contributor_failed_to_load_profile),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.previewError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            currentContributor != null && profile != null -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Image comparison
                    item {
                        ImageComparisonRow(
                            currentImagePath = currentContributor.imagePath,
                            newImageUrl = profile.imageUrl,
                            isSelected = selections.image,
                            onToggle = { onToggleField(ContributorMetadataField.IMAGE) },
                        )
                    }

                    // Name comparison
                    item {
                        TextComparisonRow(
                            label = "Name",
                            currentValue = currentContributor.name,
                            newValue = profile.name,
                            isSelected = selections.name,
                            onToggle = { onToggleField(ContributorMetadataField.NAME) },
                        )
                    }

                    // Biography comparison
                    item {
                        TextComparisonRow(
                            label = "Biography",
                            currentValue = currentContributor.description,
                            newValue = profile.biography,
                            isSelected = selections.biography,
                            onToggle = { onToggleField(ContributorMetadataField.BIOGRAPHY) },
                            isMultiline = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Image comparison row showing current and new images side-by-side.
 */
@Composable
private fun ImageComparisonRow(
    currentImagePath: String?,
    newImageUrl: String?,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val hasNewImage = !newImageUrl.isNullOrBlank()

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                enabled = hasNewImage,
            )

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.common_image),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Current image
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = currentImagePath,
                            contentDescription = stringResource(Res.string.contributor_current_image),
                            modifier =
                                Modifier
                                    .size(80.dp)
                                    .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(Res.string.contributor_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // New image (or placeholder)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (hasNewImage) {
                            AsyncImage(
                                model = newImageUrl,
                                contentDescription = stringResource(Res.string.contributor_new_image),
                                modifier =
                                    Modifier
                                        .size(80.dp)
                                        .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            // Placeholder when no image available
                            Surface(
                                modifier =
                                    Modifier
                                        .size(80.dp)
                                        .clip(CircleShape),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(Res.string.contributor_audible),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (hasNewImage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                        )
                    }
                }

                if (!hasNewImage) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.contributor_no_image_available_from_audible),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Text comparison row showing current and new values.
 */
@Composable
private fun TextComparisonRow(
    label: String,
    currentValue: String?,
    newValue: String?,
    isSelected: Boolean,
    onToggle: () -> Unit,
    isMultiline: Boolean = false,
) {
    val hasNewValue = !newValue.isNullOrBlank()
    val isUnchanged = currentValue == newValue

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                enabled = hasNewValue && !isUnchanged,
            )

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (isUnchanged && hasNewValue) {
                        Text(
                            text = stringResource(Res.string.contributor_no_change),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Current value
                Text(
                    text = stringResource(Res.string.contributor_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentValue?.ifBlank { "(empty)" } ?: "(empty)",
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (currentValue.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = if (isMultiline) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(12.dp))

                // New value
                Text(
                    text = stringResource(Res.string.contributor_audible),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = newValue?.ifBlank { "(empty)" } ?: "(empty)",
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (newValue.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = if (isMultiline) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
