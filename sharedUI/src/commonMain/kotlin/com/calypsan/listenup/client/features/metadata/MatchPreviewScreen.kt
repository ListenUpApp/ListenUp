@file:Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "CognitiveComplexMethod")

package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.CoverOption
import com.calypsan.listenup.client.domain.repository.MetadataBook
import com.calypsan.listenup.client.domain.repository.MetadataContributor
import com.calypsan.listenup.client.domain.repository.MetadataSeriesEntry
import com.calypsan.listenup.client.presentation.metadata.AudibleRegion
import com.calypsan.listenup.client.presentation.metadata.MetadataField
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_genres
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.common_selected
import listenup.composeapp.generated.resources.contributor_audible_region
import listenup.composeapp.generated.resources.common_loading
import listenup.composeapp.generated.resources.metadata_apply_selected_metadata
import listenup.composeapp.generated.resources.metadata_cover
import listenup.composeapp.generated.resources.metadata_current_cover
import listenup.composeapp.generated.resources.metadata_metadata_is_up_to_date
import listenup.composeapp.generated.resources.metadata_no_metadata_available
import listenup.composeapp.generated.resources.metadata_release_date
import listenup.composeapp.generated.resources.metadata_select_metadata
import listenup.composeapp.generated.resources.metadata_try_selecting_a_different_region
import listenup.composeapp.generated.resources.metadata_your_book_already_has_all

/**
 * Full-screen preview of metadata changes before applying.
 *
 * Shows all available metadata fields with checkboxes so users can
 * select which fields to apply. Supports region selection for
 * trying different Audible markets.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MatchPreviewScreen(
    currentBook: BookDetail,
    newMetadata: MetadataBook,
    selections: MetadataSelections,
    isApplying: Boolean,
    applyError: String?,
    previewNotFound: Boolean,
    selectedRegion: AudibleRegion,
    // Cover selection
    coverOptions: List<CoverOption>,
    isLoadingCovers: Boolean,
    selectedCoverUrl: String?,
    onSelectCover: (String?) -> Unit,
    // Callbacks
    onRegionSelected: (AudibleRegion) -> Unit,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
) {
    // Check if any field is selected
    val hasAnySelected =
        selections.cover ||
            selections.title ||
            selections.subtitle ||
            selections.description ||
            selections.publisher ||
            selections.releaseDate ||
            selections.language ||
            selections.selectedAuthors.isNotEmpty() ||
            selections.selectedNarrators.isNotEmpty() ||
            selections.selectedSeries.isNotEmpty() ||
            selections.selectedGenres.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.metadata_select_metadata)) },
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
                    applyError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    Button(
                        onClick = onApply,
                        enabled = !isApplying && hasAnySelected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isApplying) {
                            ListenUpLoadingIndicatorSmall()
                        } else {
                            Text(stringResource(Res.string.metadata_apply_selected_metadata))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Region selector
            item {
                RegionSelector(
                    selectedRegion = selectedRegion,
                    onRegionSelected = onRegionSelected,
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
            }

            // Check if there's any metadata available
            val hasAnyData =
                newMetadata.coverUrl != null ||
                    newMetadata.title.isNotBlank() ||
                    !newMetadata.subtitle.isNullOrBlank() ||
                    newMetadata.authors.isNotEmpty() ||
                    newMetadata.narrators.isNotEmpty() ||
                    newMetadata.series.isNotEmpty() ||
                    newMetadata.genres.isNotEmpty() ||
                    !newMetadata.description.isNullOrBlank() ||
                    !newMetadata.publisher.isNullOrBlank() ||
                    !newMetadata.language.isNullOrBlank() ||
                    !newMetadata.releaseDate.isNullOrBlank()

            if (!hasAnyData) {
                item {
                    if (previewNotFound) {
                        NoMetadataAvailableMessage(selectedRegion = selectedRegion)
                    } else {
                        AlreadyUpToDateMessage()
                    }
                }
            } else {
                // Cover selection row
                item {
                    CoverSelectionRow(
                        currentCoverPath = currentBook.coverPath,
                        coverOptions = coverOptions,
                        isLoading = isLoadingCovers,
                        selectedUrl = selectedCoverUrl,
                        isCoverEnabled = selections.cover,
                        onSelectCover = onSelectCover,
                        onToggleCover = { onToggleField(MetadataField.COVER) },
                    )
                }

                // Title
                if (newMetadata.title.isNotBlank()) {
                    item {
                        SimpleFieldItem(
                            label = "Title",
                            value = newMetadata.title,
                            isSelected = selections.title,
                            onToggle = { onToggleField(MetadataField.TITLE) },
                        )
                    }
                }

                // Subtitle
                newMetadata.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    item {
                        SimpleFieldItem(
                            label = "Subtitle",
                            value = subtitle,
                            isSelected = selections.subtitle,
                            onToggle = { onToggleField(MetadataField.SUBTITLE) },
                        )
                    }
                }

                // Authors
                if (newMetadata.authors.isNotEmpty()) {
                    item {
                        ContributorListItem(
                            label = "Authors",
                            contributors = newMetadata.authors,
                            selectedAsins = selections.selectedAuthors,
                            onToggle = onToggleAuthor,
                        )
                    }
                }

                // Narrators
                if (newMetadata.narrators.isNotEmpty()) {
                    item {
                        ContributorListItem(
                            label = "Narrators",
                            contributors = newMetadata.narrators,
                            selectedAsins = selections.selectedNarrators,
                            onToggle = onToggleNarrator,
                        )
                    }
                }

                // Series
                if (newMetadata.series.isNotEmpty()) {
                    item {
                        SeriesListItem(
                            series = newMetadata.series,
                            selectedAsins = selections.selectedSeries,
                            onToggle = onToggleSeries,
                        )
                    }
                }

                // Genres
                if (newMetadata.genres.isNotEmpty()) {
                    item {
                        GenreListItem(
                            genres = newMetadata.genres,
                            selectedGenres = selections.selectedGenres,
                            onToggle = onToggleGenre,
                        )
                    }
                }

                // Description
                newMetadata.description?.takeIf { it.isNotBlank() }?.let { description ->
                    item {
                        val displayText =
                            if (description.length > 200) {
                                description.take(200) + "..."
                            } else {
                                description
                            }
                        SimpleFieldItem(
                            label = "Description",
                            value = displayText,
                            isSelected = selections.description,
                            onToggle = { onToggleField(MetadataField.DESCRIPTION) },
                        )
                    }
                }

                // Publisher
                newMetadata.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
                    item {
                        SimpleFieldItem(
                            label = "Publisher",
                            value = publisher,
                            isSelected = selections.publisher,
                            onToggle = { onToggleField(MetadataField.PUBLISHER) },
                        )
                    }
                }

                // Release Date
                newMetadata.releaseDate?.takeIf { it.isNotBlank() }?.let { releaseDate ->
                    item {
                        SimpleFieldItem(
                            label = stringResource(Res.string.metadata_release_date),
                            value = releaseDate,
                            isSelected = selections.releaseDate,
                            onToggle = { onToggleField(MetadataField.RELEASE_DATE) },
                        )
                    }
                }

                // Language
                newMetadata.language?.takeIf { it.isNotBlank() }?.let { language ->
                    item {
                        SimpleFieldItem(
                            label = "Language",
                            value = language,
                            isSelected = selections.language,
                            onToggle = { onToggleField(MetadataField.LANGUAGE) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Region selector chips for trying different Audible markets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegionSelector(
    selectedRegion: AudibleRegion,
    onRegionSelected: (AudibleRegion) -> Unit,
) {
    Column {
        Text(
            text = stringResource(Res.string.contributor_audible_region),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AudibleRegion.entries.forEach { region ->
                FilterChip(
                    selected = region == selectedRegion,
                    onClick = { onRegionSelected(region) },
                    label = { Text(region.displayName) },
                )
            }
        }
    }
}

/**
 * Horizontal scrollable row of cover options from multiple sources.
 * Shows current cover first, then all available options from iTunes/Audible.
 */
@Composable
private fun CoverSelectionRow(
    currentCoverPath: String?,
    coverOptions: List<CoverOption>,
    isLoading: Boolean,
    selectedUrl: String?,
    isCoverEnabled: Boolean,
    onSelectCover: (String?) -> Unit,
    onToggleCover: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header with checkbox
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isCoverEnabled,
                onCheckedChange = { onToggleCover() },
            )
            Text(
                text = stringResource(Res.string.metadata_cover),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable cover options
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp),
        ) {
            // Current cover option (selecting this keeps current cover)
            if (currentCoverPath != null) {
                item {
                    CoverOptionCard(
                        label = "Current",
                        source = null,
                        width = null,
                        height = null,
                        isSelected = selectedUrl == null && isCoverEnabled,
                        onClick = { onSelectCover(null) },
                    ) {
                        ListenUpAsyncImage(
                            path = currentCoverPath,
                            contentDescription = stringResource(Res.string.metadata_current_cover),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Cover options from search
            items(coverOptions) { cover ->
                CoverOptionCard(
                    label = cover.source.replaceFirstChar { it.uppercase() },
                    source = cover.source,
                    width = cover.width,
                    height = cover.height,
                    isSelected = selectedUrl == cover.url,
                    onClick = { onSelectCover(cover.url) },
                ) {
                    AsyncImage(
                        model = cover.url,
                        contentDescription = "Cover from ${cover.source}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            // Loading placeholders
            if (isLoading) {
                items(3) {
                    CoverOptionPlaceholder()
                }
            }
        }
    }
}

/**
 * Individual cover option card with image, source badge, and dimensions.
 */
@Composable
private fun CoverOptionCard(
    label: String,
    source: String?,
    width: Int?,
    height: Int?,
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier =
                Modifier
                    .size(100.dp)
                    .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.medium,
            border =
                if (isSelected) {
                    BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
            elevation =
                CardDefaults.cardElevation(
                    defaultElevation = if (isSelected) 4.dp else 1.dp,
                ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                // Source badge
                if (source != null) {
                    Surface(
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }

                // Selected indicator
                if (isSelected) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(Res.string.common_selected),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Dimensions or label
        Text(
            text = if (width != null && height != null) "$width×$height" else label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/**
 * Placeholder card shown while covers are loading.
 */
@Composable
private fun CoverOptionPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.size(100.dp),
            shape = MaterialTheme.shapes.medium,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ListenUpLoadingIndicatorSmall()
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(Res.string.common_loading),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Simple field with checkbox (title, subtitle, description, etc.).
 */
@Composable
private fun SimpleFieldItem(
    label: String,
    value: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
        )
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * List of contributors (authors/narrators) with individual checkboxes.
 */
@Composable
private fun ContributorListItem(
    label: String,
    contributors: List<MetadataContributor>,
    selectedAsins: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        contributors.forEach { contributor ->
            val asin = contributor.asin ?: contributor.name // Fallback to name if no ASIN
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = asin in selectedAsins,
                    onCheckedChange = { onToggle(asin) },
                )
                Text(
                    text = contributor.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * List of series with individual checkboxes.
 */
@Composable
private fun SeriesListItem(
    series: List<MetadataSeriesEntry>,
    selectedAsins: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.common_series),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        series.forEach { seriesEntry ->
            val asin = seriesEntry.asin ?: seriesEntry.name // Fallback to name if no ASIN
            val displayText =
                if (seriesEntry.position != null) {
                    "${seriesEntry.name} #${seriesEntry.position}"
                } else {
                    seriesEntry.name
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = asin in selectedAsins,
                    onCheckedChange = { onToggle(asin) },
                )
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * List of genres with individual checkboxes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreListItem(
    genres: List<String>,
    selectedGenres: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.common_genres),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            genres.forEach { genre ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = genre in selectedGenres,
                        onCheckedChange = { onToggle(genre) },
                    )
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Message shown when the book is found on Audible but has no/minimal metadata.
 */
@Composable
private fun NoMetadataAvailableMessage(selectedRegion: AudibleRegion) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.metadata_no_metadata_available),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                "This book exists on Audible (${selectedRegion.displayName}) but has minimal metadata. " +
                    stringResource(Res.string.metadata_try_selecting_a_different_region),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Message shown when the book's metadata is already up to date.
 */
@Composable
private fun AlreadyUpToDateMessage() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.metadata_metadata_is_up_to_date),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.metadata_your_book_already_has_all),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
