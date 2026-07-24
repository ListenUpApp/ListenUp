package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorPreviewLoadState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_image
import listenup.composeapp.generated.resources.contributor_apply_match
import listenup.composeapp.generated.resources.contributor_audible
import listenup.composeapp.generated.resources.contributor_change_match
import listenup.composeapp.generated.resources.contributor_current
import listenup.composeapp.generated.resources.contributor_current_image
import listenup.composeapp.generated.resources.contributor_failed_to_load_profile
import listenup.composeapp.generated.resources.contributor_new_image
import listenup.composeapp.generated.resources.contributor_no_profile_in_region
import listenup.composeapp.generated.resources.contributor_preview_changes
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen preview of contributor metadata changes before applying.
 *
 * Exhaustive over [ContributorPreviewLoadState]: Loading spinner, a Missing state offering a
 * region switch (Never-Stranded — an empty regional shell is an honest miss, not a blank
 * preview), a Failed state, and the Ready compare view. There are no per-field checkboxes:
 * the server applies asin + biography + photo, never the name — the compare rows are
 * informational. The Apply bar renders ONLY in Ready, so a non-ready state can never sit
 * above a live Apply button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorMetadataPreviewScreen(
    state: ContributorMetadataUiState.Preview,
    onRegionSelected: (MetadataLocale) -> Unit,
    onApply: () -> Unit,
    onChangeMatch: () -> Unit,
    onBack: () -> Unit,
) {
    val ready = state.loadState as? ContributorPreviewLoadState.Ready

    ListenUpScaffold(
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
            if (ready != null) {
                PreviewBottomBar(
                    applyError = ready.applyError,
                    isApplying = ready.isApplying,
                    onApply = onApply,
                    onChangeMatch = onChangeMatch,
                )
            }
        },
    ) { padding ->
        when (val loadState = state.loadState) {
            is ContributorPreviewLoadState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    ListenUpLoadingIndicator()
                }
            }

            is ContributorPreviewLoadState.Missing -> {
                MissingProfileContent(
                    selectedRegion = state.region,
                    onRegionSelected = onRegionSelected,
                    onChangeMatch = onChangeMatch,
                    padding = padding,
                )
            }

            is ContributorPreviewLoadState.Failed -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
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
                            text = loadState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            is ContributorPreviewLoadState.Ready -> {
                ReadyContent(
                    currentName = state.context.current?.name,
                    currentDescription = state.context.current?.description,
                    currentImagePath = state.context.current?.imagePath,
                    profile = loadState.profile,
                    padding = padding,
                )
            }
        }
    }
}

/** The honest-miss state: no profile data in this region, offer the other regions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissingProfileContent(
    selectedRegion: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
    onChangeMatch: () -> Unit,
    padding: PaddingValues,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.contributor_no_profile_in_region, selectedRegion.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetadataLocale.SUPPORTED.forEach { region ->
                    FilterChip(
                        selected = region == selectedRegion,
                        onClick = { onRegionSelected(region) },
                        label = { Text(region.displayName) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onChangeMatch) {
                Text(stringResource(Res.string.contributor_change_match))
            }
        }
    }
}

@Composable
private fun PreviewBottomBar(
    applyError: String?,
    isApplying: Boolean,
    onApply: () -> Unit,
    onChangeMatch: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            applyError?.let { error ->
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
                OutlinedButton(onClick = onChangeMatch, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.contributor_change_match))
                }
                Button(
                    onClick = onApply,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isApplying) {
                        ListenUpLoadingIndicatorSmall(color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(Res.string.contributor_apply_match))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    currentName: String?,
    currentDescription: String?,
    currentImagePath: String?,
    profile: MetadataContributorProfile,
    padding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ImageComparisonRow(
                currentImagePath = currentImagePath,
                newImageUrl = profile.imageUrl,
            )
        }
        item {
            TextComparisonRow(
                label = "Name",
                currentValue = currentName,
                newValue = profile.name,
            )
        }
        item {
            TextComparisonRow(
                label = "Biography",
                currentValue = currentDescription,
                newValue = profile.description,
                isMultiline = true,
            )
        }
    }
}

/** Side-by-side current vs. incoming photo. Informational — no toggle; the server keeps the existing photo when the incoming one is absent. */
@Composable
private fun ImageComparisonRow(
    currentImagePath: String?,
    newImageUrl: String?,
) {
    val hasNewImage = !newImageUrl.isNullOrBlank()

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = currentImagePath,
                        contentDescription = stringResource(Res.string.contributor_current_image),
                        modifier = Modifier.size(80.dp).clip(CircleShape),
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hasNewImage) {
                        AsyncImage(
                            model = newImageUrl,
                            contentDescription = stringResource(Res.string.contributor_new_image),
                            modifier = Modifier.size(80.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(80.dp).clip(CircleShape),
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
        }
    }
}

private const val EMPTY_VALUE_PLACEHOLDER = "(empty)"

/** Side-by-side current vs. incoming text value. Informational — no toggle. */
@Composable
private fun TextComparisonRow(
    label: String,
    currentValue: String?,
    newValue: String?,
    isMultiline: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            ComparisonValue(
                labelText = stringResource(Res.string.contributor_current),
                value = currentValue,
                isMultiline = isMultiline,
                accent = false,
            )
            Spacer(Modifier.height(12.dp))
            ComparisonValue(
                labelText = stringResource(Res.string.contributor_audible),
                value = newValue,
                isMultiline = isMultiline,
                accent = true,
            )
        }
    }
}

/** One labelled value line within [TextComparisonRow], with an empty-value placeholder. */
@Composable
private fun ComparisonValue(
    labelText: String,
    value: String?,
    isMultiline: Boolean,
    accent: Boolean,
) {
    Text(
        text = labelText,
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = value?.ifBlank { EMPTY_VALUE_PLACEHOLDER } ?: EMPTY_VALUE_PLACEHOLDER,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (value.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        maxLines = if (isMultiline) 6 else 2,
        overflow = TextOverflow.Ellipsis,
    )
}
