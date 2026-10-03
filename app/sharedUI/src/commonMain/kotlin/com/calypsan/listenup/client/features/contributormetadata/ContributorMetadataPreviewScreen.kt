package com.calypsan.listenup.client.features.contributormetadata

import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorPreviewLoadState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_image
import listenup.composeapp.generated.resources.contributor_biography
import listenup.composeapp.generated.resources.contributor_apply_match
import listenup.composeapp.generated.resources.contributor_audible
import listenup.composeapp.generated.resources.contributor_change_match
import listenup.composeapp.generated.resources.contributor_current
import listenup.composeapp.generated.resources.contributor_current_image
import listenup.composeapp.generated.resources.contributor_failed_to_load_profile
import listenup.composeapp.generated.resources.contributor_new_image
import listenup.composeapp.generated.resources.contributor_no_change
import listenup.composeapp.generated.resources.contributor_no_profile_in_region
import listenup.composeapp.generated.resources.contributor_preview_changes
import listenup.composeapp.generated.resources.metadata_audible_region
import listenup.composeapp.generated.resources.metadata_audible_source_region
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen preview of contributor metadata changes before applying.
 *
 * Exhaustive over [ContributorPreviewLoadState]: Loading spinner, a Missing state offering a
 * region switch (Never-Stranded — an empty regional shell is an honest miss, not a blank
 * preview), a Failed state, and the Ready compare view. There are no per-field checkboxes.
 *
 * Apply updates the biography and the photo — it never renames the contributor. The server's
 * applier writes exactly asin, biography and photo, and never blanks an existing value with a
 * missing incoming one. So the matched name is shown as *identification* — which person was
 * matched, and from which Audible region — rather than as a before-and-after, which would promise
 * a rename that never happens. Only the photo and the biography are compared, and an identical
 * biography says "No change". The region can be switched from Ready too — Audible localises
 * contributor profiles, so another region may hold a better one — and Apply applies the profile of
 * the region on screen. The Apply actions
 * render ONLY in Ready, so a non-ready state can never sit above a live Apply button.
 *
 * A phone stacks the identification and the comparisons above a bottom action bar. From the
 * expanded width, who was matched — the identification, the photo, and the actions that commit to
 * them — becomes a side panel,
 * and the two biographies are read in full, side by side, beside it: the biography is the long field
 * and the one worth comparing line by line.
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
    val haptics = LocalHaptics.current
    val ready = state.loadState as? ContributorPreviewLoadState.Ready
    val panelBeside =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    ListenUpScaffold(
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.contributor_preview_changes),
                onBack = onBack,
            )
        },
        bottomBar = {
            if (ready != null && !panelBeside) {
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
                if (panelBeside) {
                    ReadyWideContent(
                        region = state.region,
                        onRegionSelected = onRegionSelected,
                        currentDescription = state.context.current?.description,
                        currentImagePath = state.context.current?.imagePath,
                        profile = loadState.profile,
                        padding = padding,
                        actions = {
                            PreviewActions(
                                applyError = loadState.applyError,
                                isApplying = loadState.isApplying,
                                onApply = onApply,
                                onChangeMatch = onChangeMatch,
                            )
                        },
                    )
                } else {
                    ReadyContent(
                        region = state.region,
                        onRegionSelected = onRegionSelected,
                        currentDescription = state.context.current?.description,
                        currentImagePath = state.context.current?.imagePath,
                        profile = loadState.profile,
                        padding = padding,
                    )
                }
            }
        }
    }
}

/** The honest-miss state: no profile data in this region, offer the other regions. */
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
            RegionChips(selectedRegion = selectedRegion, onRegionSelected = onRegionSelected)
            Spacer(Modifier.height(Spacing.xl))
            OutlinedButton(onClick = onChangeMatch, border = listenUpOutlinedBorder()) {
                Text(stringResource(Res.string.contributor_change_match))
            }
        }
    }
}

/** One chip per supported Audible region — the region switch, in the Missing state and in Ready. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegionChips(
    selectedRegion: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
) {
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
}

/** The Ready preview's region switch: a quiet label over [RegionChips]. */
@Composable
private fun ReadyRegionSwitch(
    selectedRegion: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.metadata_audible_region),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RegionChips(selectedRegion = selectedRegion, onRegionSelected = onRegionSelected)
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
        PreviewActions(
            applyError = applyError,
            isApplying = isApplying,
            onApply = onApply,
            onChangeMatch = onChangeMatch,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

/** The apply error, if any, over Change Match and Apply — the phone's bottom bar, or the foot of the wide panel. */
@Composable
private fun PreviewActions(
    applyError: String?,
    isApplying: Boolean,
    onApply: () -> Unit,
    onChangeMatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
            OutlinedButton(onClick = onChangeMatch, border = listenUpOutlinedBorder(), modifier = Modifier.weight(1f)) {
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

@Composable
private fun ReadyContent(
    region: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
    currentDescription: String?,
    currentImagePath: String?,
    profile: MetadataContributorProfile,
    padding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { MatchedIdentity(name = profile.name, region = region) }
        item { ReadyRegionSwitch(selectedRegion = region, onRegionSelected = onRegionSelected) }
        item {
            ImageComparisonRow(
                currentImagePath = currentImagePath,
                newImageUrl = profile.imageUrl,
            )
        }
        item {
            TextComparisonRow(
                label = stringResource(Res.string.contributor_biography),
                currentValue = currentDescription,
                newValue = profile.description,
                isMultiline = true,
            )
        }
    }
}

/** Width of the wide layout's identity panel — one comfortable card column. */
private val IdentityPanelWidth = 360.dp

/**
 * The expanded-width Ready view: an identity panel (who was matched, the region switch, their photo, then
 * [actions]) beside the two
 * biographies read in full, side by side.
 */
@Suppress("LongParameterList")
@Composable
private fun ReadyWideContent(
    region: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
    currentDescription: String?,
    currentImagePath: String?,
    profile: MetadataContributorProfile,
    padding: PaddingValues,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        Column(
            modifier =
                Modifier
                    .width(IdentityPanelWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MatchedIdentity(name = profile.name, region = region)
            ReadyRegionSwitch(selectedRegion = region, onRegionSelected = onRegionSelected)
            ImageComparisonRow(
                currentImagePath = currentImagePath,
                newImageUrl = profile.imageUrl,
            )
            actions()
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
        ) {
            BiographyComparison(currentValue = currentDescription, newValue = profile.description)
        }
    }
}

/**
 * Who was matched: the Audible profile's name, prominent, over a quiet "Audible · <region>" source
 * line. Identification only — Apply never renames the contributor, so there is no before-and-after.
 */
@Composable
private fun MatchedIdentity(
    name: String,
    region: MetadataLocale,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.metadata_audible_source_region, region.displayName),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The current and incoming biographies in two columns, unclamped — the wide layout has the room to read both. */
@Composable
private fun BiographyComparison(
    currentValue: String?,
    newValue: String?,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            ComparisonHeader(
                label = stringResource(Res.string.contributor_biography),
                isUnchanged = isUnchanged(currentValue, newValue),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sectionGap)) {
                Column(modifier = Modifier.weight(1f)) {
                    ComparisonValue(
                        labelText = stringResource(Res.string.contributor_current),
                        value = currentValue,
                        maxLines = Int.MAX_VALUE,
                        accent = false,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    ComparisonValue(
                        labelText = stringResource(Res.string.contributor_audible),
                        value = newValue,
                        maxLines = Int.MAX_VALUE,
                        accent = true,
                    )
                }
            }
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
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
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
                                    tint = MaterialTheme.colorScheme.outline,
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
                                MaterialTheme.colorScheme.onSurfaceVariant
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
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            ComparisonHeader(label = label, isUnchanged = isUnchanged(currentValue, newValue))
            Spacer(Modifier.height(8.dp))
            ComparisonValue(
                labelText = stringResource(Res.string.contributor_current),
                value = currentValue,
                maxLines = if (isMultiline) 6 else 2,
                accent = false,
            )
            Spacer(Modifier.height(12.dp))
            ComparisonValue(
                labelText = stringResource(Res.string.contributor_audible),
                value = newValue,
                maxLines = if (isMultiline) 6 else 2,
                accent = true,
            )
        }
    }
}

/**
 * Whether Apply would leave this value exactly as it is. Two empty values are not "no change" —
 * there is nothing to keep — so only an identical, non-blank pair counts. Mirrors iOS and web.
 */
private fun isUnchanged(
    currentValue: String?,
    newValue: String?,
): Boolean = !newValue.isNullOrBlank() && currentValue == newValue

/** A comparison's label, with a quiet "No change" at the end when both sides are identical. */
@Composable
private fun ComparisonHeader(
    label: String,
    isUnchanged: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isUnchanged) {
            Text(
                text = stringResource(Res.string.contributor_no_change),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One labelled value line within [TextComparisonRow], with an empty-value placeholder. */
@Composable
private fun ComparisonValue(
    labelText: String,
    value: String?,
    maxLines: Int,
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
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
