package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import com.calypsan.listenup.client.presentation.metadata.CoverEntry
import com.calypsan.listenup.client.presentation.metadata.MetadataField
import com.calypsan.listenup.client.presentation.metadata.MetadataSelections
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_loading
import listenup.composeapp.generated.resources.common_selected
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.metadata_apply_selected_metadata
import listenup.composeapp.generated.resources.metadata_audible_region
import listenup.composeapp.generated.resources.metadata_audible_source_region
import listenup.composeapp.generated.resources.metadata_chapter_names
import listenup.composeapp.generated.resources.metadata_chapters_matched
import listenup.composeapp.generated.resources.metadata_cover
import listenup.composeapp.generated.resources.metadata_cover_from_source
import listenup.composeapp.generated.resources.metadata_current_cover
import listenup.composeapp.generated.resources.metadata_cover_source_resolution
import listenup.composeapp.generated.resources.metadata_field_authors
import listenup.composeapp.generated.resources.metadata_field_description
import listenup.composeapp.generated.resources.metadata_field_genres
import listenup.composeapp.generated.resources.metadata_field_language
import listenup.composeapp.generated.resources.metadata_field_moods
import listenup.composeapp.generated.resources.metadata_field_narrators
import listenup.composeapp.generated.resources.metadata_field_publisher
import listenup.composeapp.generated.resources.metadata_field_source
import listenup.composeapp.generated.resources.metadata_field_tags
import listenup.composeapp.generated.resources.metadata_field_subtitle
import listenup.composeapp.generated.resources.metadata_field_title
import listenup.composeapp.generated.resources.metadata_merged_from
import listenup.composeapp.generated.resources.metadata_metadata_is_up_to_date
import listenup.composeapp.generated.resources.metadata_no_metadata_available
import listenup.composeapp.generated.resources.metadata_release_date
import listenup.composeapp.generated.resources.metadata_review
import listenup.composeapp.generated.resources.metadata_review_and_apply_chapter_names
import listenup.composeapp.generated.resources.metadata_section_classification
import listenup.composeapp.generated.resources.metadata_section_details
import listenup.composeapp.generated.resources.metadata_section_identity
import listenup.composeapp.generated.resources.metadata_select_metadata
import listenup.composeapp.generated.resources.metadata_try_selecting_a_different_region
import listenup.composeapp.generated.resources.metadata_your_book_already_has_all

/** Readable centred-column width cap on expanded layouts. */
private val PREVIEW_MAX_WIDTH = 640.dp
private const val DESCRIPTION_PREVIEW_LIMIT = 200

/**
 * Full-screen preview of metadata changes before applying.
 *
 * Shows all available metadata fields with checkboxes so users can
 * select which fields to apply. Supports region selection for
 * trying different Audible markets.
 */
@OptIn(ExperimentalMaterial3Api::class)
// Screen entry point hoists every metadata field's state + toggle callback; a parameter object
// would only add an indirection layer Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
fun MatchPreviewScreen(
    currentBook: BookDetail,
    newMetadata: MetadataBook,
    selections: MetadataSelections,
    isApplying: Boolean,
    applyError: String?,
    previewNotFound: Boolean,
    selectedRegion: MetadataLocale,
    // Cover selection
    coverOptions: List<CoverEntry>,
    isLoadingCovers: Boolean,
    selectedCoverUrl: String?,
    onSelectCover: (String?) -> Unit,
    // Chapter names
    chapterSuggestion: ChapterSuggestion,
    onReviewChapters: () -> Unit,
    // Provenance
    fallbackSources: Map<BookField, String>,
    coverSourceLabel: String?,
    coverResolution: String?,
    contributingSources: List<String>,
    // Callbacks
    onRegionSelected: (MetadataLocale) -> Unit,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onToggleMood: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
) {
    val hasAnySelected = selections.hasAnySelected()

    ListenUpScaffold(
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.metadata_select_metadata),
                badgeIcon = Icons.AutoMirrored.Outlined.MenuBook,
                onBack = onBack,
            )
        },
        bottomBar = {
            ApplyBottomBar(
                applyError = applyError,
                isApplying = isApplying,
                hasAnySelected = hasAnySelected,
                contributingSources = contributingSources,
                onApply = onApply,
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = PREVIEW_MAX_WIDTH),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Matched edition hero
                item {
                    MatchedEditionHero(
                        match = newMetadata,
                        coverUrl = newMetadata.coverUrl,
                        selectedRegion = selectedRegion,
                    )
                }

                // Region selector
                item {
                    Column {
                        Overline(text = stringResource(Res.string.metadata_audible_region))
                        Spacer(modifier = Modifier.height(12.dp))
                        RegionSelector(
                            selectedRegion = selectedRegion,
                            onRegionSelected = onRegionSelected,
                        )
                    }
                }

                if (!newMetadata.hasAnyData()) {
                    item {
                        if (previewNotFound) {
                            NoMetadataAvailableMessage(selectedRegion = selectedRegion)
                        } else {
                            AlreadyUpToDateMessage()
                        }
                    }
                } else {
                    metadataFieldsSection(
                        currentBook = currentBook,
                        newMetadata = newMetadata,
                        selections = selections,
                        coverOptions = coverOptions,
                        isLoadingCovers = isLoadingCovers,
                        selectedCoverUrl = selectedCoverUrl,
                        onSelectCover = onSelectCover,
                        chapterSuggestion = chapterSuggestion,
                        onReviewChapters = onReviewChapters,
                        fallbackSources = fallbackSources,
                        coverSourceLabel = coverSourceLabel,
                        coverResolution = coverResolution,
                        onToggleField = onToggleField,
                        onToggleAuthor = onToggleAuthor,
                        onToggleNarrator = onToggleNarrator,
                        onToggleSeries = onToggleSeries,
                        onToggleGenre = onToggleGenre,
                        onToggleMood = onToggleMood,
                        onToggleTag = onToggleTag,
                    )
                }
            }
        }
    }
}

/** True when at least one metadata field is selected for apply — gates the Apply button. */
private fun MetadataSelections.hasAnySelected(): Boolean =
    cover ||
        title ||
        subtitle ||
        description ||
        publisher ||
        releaseDate ||
        language ||
        selectedAuthors.isNotEmpty() ||
        selectedNarrators.isNotEmpty() ||
        selectedSeries.isNotEmpty() ||
        selectedGenres.isNotEmpty() ||
        selectedMoods.isNotEmpty() ||
        selectedTags.isNotEmpty()

/** True when the match carries any displayable metadata — gates the "no metadata" placeholder. */
private fun MetadataBook.hasAnyData(): Boolean =
    coverUrl != null ||
        title.isNotBlank() ||
        !subtitle.isNullOrBlank() ||
        authors.isNotEmpty() ||
        narrators.isNotEmpty() ||
        series.isNotEmpty() ||
        genres.isNotEmpty() ||
        moods.isNotEmpty() ||
        tags.isNotEmpty() ||
        !description.isNullOrBlank() ||
        !publisher.isNullOrBlank() ||
        !language.isNullOrBlank() ||
        !releaseDate.isNullOrBlank()

/** UPPERCASE muted overline label, matching the grouped-section heading idiom. */
@Composable
private fun Overline(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
    )
}

/**
 * The matched-edition color-blocked hero: a [MaterialTheme.colorScheme.primaryContainer] card with
 * the matched cover, an "Audible · region" source chip, the title, and the contributor line.
 */
@Composable
private fun MatchedEditionHero(
    match: MetadataBook,
    coverUrl: String?,
    selectedRegion: MetadataLocale,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(74.dp)
                            .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(74.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(colors.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = colors.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                SourceChip(region = selectedRegion)
                Text(
                    text = match.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 9.dp),
                )
                val people =
                    (match.authors.map { it.name } + match.narrators.map { it.name })
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                if (people.isNotBlank()) {
                    Text(
                        text = people,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The "Audible · <region>" provenance chip shown on the matched-edition hero. */
@Composable
private fun SourceChip(region: MetadataLocale) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = colors.onPrimaryContainer.copy(alpha = 0.12f),
        contentColor = colors.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 11.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(Res.string.metadata_audible_source_region, region.displayName),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ApplyBottomBar(
    applyError: String?,
    isApplying: Boolean,
    hasAnySelected: Boolean,
    contributingSources: List<String>,
    onApply: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
        ) {
            if (contributingSources.size > 1) {
                Text(
                    text = stringResource(Res.string.metadata_merged_from, contributingSources.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            applyError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            ListenUpButton(
                text = stringResource(Res.string.metadata_apply_selected_metadata),
                onClick = onApply,
                enabled = hasAnySelected,
                isLoading = isApplying,
                leadingIcon = Icons.Outlined.Check,
            )
        }
    }
}

// Emits one LazyColumn item per available metadata field; the callback set is fanned straight
// to each row, so a parameter object would only add an indirection layer Compose tooling discourages.
@Suppress("LongParameterList")
private fun LazyListScope.metadataFieldsSection(
    currentBook: BookDetail,
    newMetadata: MetadataBook,
    selections: MetadataSelections,
    coverOptions: List<CoverEntry>,
    isLoadingCovers: Boolean,
    selectedCoverUrl: String?,
    onSelectCover: (String?) -> Unit,
    chapterSuggestion: ChapterSuggestion,
    onReviewChapters: () -> Unit,
    fallbackSources: Map<BookField, String>,
    coverSourceLabel: String?,
    coverResolution: String?,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onToggleMood: (String) -> Unit,
    onToggleTag: (String) -> Unit,
) {
    // ── IDENTITY ──
    item {
        FieldGroup(
            label = stringResource(Res.string.metadata_section_identity),
            icon = Icons.AutoMirrored.Outlined.MenuBook,
        ) {
            IdentitySectionContent(
                currentBook = currentBook,
                newMetadata = newMetadata,
                selections = selections,
                coverOptions = coverOptions,
                isLoadingCovers = isLoadingCovers,
                selectedCoverUrl = selectedCoverUrl,
                onSelectCover = onSelectCover,
                fallbackSources = fallbackSources,
                coverSourceLabel = coverSourceLabel,
                coverResolution = coverResolution,
                onToggleField = onToggleField,
                onToggleAuthor = onToggleAuthor,
                onToggleNarrator = onToggleNarrator,
                onToggleSeries = onToggleSeries,
            )
        }
    }

    // ── CLASSIFICATION ──
    val hasClassification =
        newMetadata.genres.isNotEmpty() ||
            newMetadata.moods.isNotEmpty() ||
            newMetadata.tags.isNotEmpty()
    if (hasClassification) {
        item {
            FieldGroup(
                label = stringResource(Res.string.metadata_section_classification),
                icon = Icons.Outlined.Category,
                accent = MaterialTheme.colorScheme.tertiary,
            ) {
                if (newMetadata.genres.isNotEmpty()) {
                    GenreFieldRow(
                        genres = newMetadata.genres,
                        selectedGenres = selections.selectedGenres,
                        onToggle = onToggleGenre,
                        sourceLabel = fallbackSources[BookField.GENRES],
                    )
                }
                if (newMetadata.moods.isNotEmpty()) {
                    MoodFieldRow(
                        moods = newMetadata.moods,
                        selectedMoods = selections.selectedMoods,
                        onToggle = onToggleMood,
                    )
                }
                if (newMetadata.tags.isNotEmpty()) {
                    TagFieldRow(
                        tags = newMetadata.tags,
                        selectedTags = selections.selectedTags,
                        onToggle = onToggleTag,
                    )
                }
            }
        }
    }

    // ── DETAILS ──
    val hasDetails =
        !newMetadata.description.isNullOrBlank() ||
            !newMetadata.publisher.isNullOrBlank() ||
            !newMetadata.releaseDate.isNullOrBlank() ||
            !newMetadata.language.isNullOrBlank()
    if (hasDetails) {
        item {
            FieldGroup(
                label = stringResource(Res.string.metadata_section_details),
                icon = Icons.Outlined.Info,
                accent = MaterialTheme.colorScheme.secondary,
            ) {
                DetailsSectionContent(
                    newMetadata = newMetadata,
                    selections = selections,
                    fallbackSources = fallbackSources,
                    onToggleField = onToggleField,
                )
            }
        }
    }

    // Chapter names (count-gated; renders nothing when unavailable)
    chapterNamesSection(chapterSuggestion, onReviewChapters)
}

/** Identity-section field rows: cover, title, subtitle, authors, narrators, series. */
@Suppress("LongParameterList")
@Composable
private fun IdentitySectionContent(
    currentBook: BookDetail,
    newMetadata: MetadataBook,
    selections: MetadataSelections,
    coverOptions: List<CoverEntry>,
    isLoadingCovers: Boolean,
    selectedCoverUrl: String?,
    onSelectCover: (String?) -> Unit,
    fallbackSources: Map<BookField, String>,
    coverSourceLabel: String?,
    coverResolution: String?,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
) {
    var first = true

    CoverFieldRow(
        currentCoverPath = currentBook.coverPath,
        coverOptions = coverOptions,
        isLoading = isLoadingCovers,
        selectedUrl = selectedCoverUrl,
        isCoverEnabled = selections.cover,
        onSelectCover = onSelectCover,
        onToggleCover = { onToggleField(MetadataField.COVER) },
        coverSourceLabel = coverSourceLabel,
        coverResolution = coverResolution,
        showDivider = !first,
    )
    first = false

    if (newMetadata.title.isNotBlank()) {
        SimpleFieldRow(
            label = stringResource(Res.string.metadata_field_title),
            value = newMetadata.title,
            isSelected = selections.title,
            onToggle = { onToggleField(MetadataField.TITLE) },
            sourceLabel = fallbackSources[BookField.TITLE],
            showDivider = !first,
        )
        first = false
    }

    newMetadata.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
        SimpleFieldRow(
            label = stringResource(Res.string.metadata_field_subtitle),
            value = subtitle,
            isSelected = selections.subtitle,
            onToggle = { onToggleField(MetadataField.SUBTITLE) },
            sourceLabel = fallbackSources[BookField.SUBTITLE],
            showDivider = !first,
        )
        first = false
    }

    if (newMetadata.authors.isNotEmpty()) {
        ContributorFieldRows(
            label = stringResource(Res.string.metadata_field_authors),
            contributors = newMetadata.authors,
            selectedAsins = selections.selectedAuthors,
            onToggle = onToggleAuthor,
            sourceLabel = fallbackSources[BookField.AUTHORS],
            showTopDivider = !first,
        )
        first = false
    }

    if (newMetadata.narrators.isNotEmpty()) {
        ContributorFieldRows(
            label = stringResource(Res.string.metadata_field_narrators),
            contributors = newMetadata.narrators,
            selectedAsins = selections.selectedNarrators,
            onToggle = onToggleNarrator,
            sourceLabel = fallbackSources[BookField.NARRATORS],
            showTopDivider = !first,
        )
        first = false
    }

    if (newMetadata.series.isNotEmpty()) {
        SeriesFieldRows(
            series = newMetadata.series,
            selectedAsins = selections.selectedSeries,
            onToggle = onToggleSeries,
            sourceLabel = fallbackSources[BookField.SERIES],
            showTopDivider = !first,
        )
    }
}

/** Details-section field rows: description (clamped), publisher, release date, language. */
@Composable
private fun DetailsSectionContent(
    newMetadata: MetadataBook,
    selections: MetadataSelections,
    fallbackSources: Map<BookField, String>,
    onToggleField: (MetadataField) -> Unit,
) {
    var first = true

    newMetadata.description?.takeIf { it.isNotBlank() }?.let { description ->
        val displayText =
            if (description.length > DESCRIPTION_PREVIEW_LIMIT) {
                description.take(DESCRIPTION_PREVIEW_LIMIT) + "…"
            } else {
                description
            }
        SimpleFieldRow(
            label = stringResource(Res.string.metadata_field_description),
            value = displayText,
            isSelected = selections.description,
            onToggle = { onToggleField(MetadataField.DESCRIPTION) },
            sourceLabel = fallbackSources[BookField.DESCRIPTION],
            showDivider = !first,
        )
        first = false
    }

    newMetadata.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
        SimpleFieldRow(
            label = stringResource(Res.string.metadata_field_publisher),
            value = publisher,
            isSelected = selections.publisher,
            onToggle = { onToggleField(MetadataField.PUBLISHER) },
            sourceLabel = fallbackSources[BookField.PUBLISHER],
            showDivider = !first,
        )
        first = false
    }

    newMetadata.releaseDate?.takeIf { it.isNotBlank() }?.let { releaseDate ->
        SimpleFieldRow(
            label = stringResource(Res.string.metadata_release_date),
            value = releaseDate,
            isSelected = selections.releaseDate,
            onToggle = { onToggleField(MetadataField.RELEASE_DATE) },
            sourceLabel = fallbackSources[BookField.PUBLISH_YEAR],
            showDivider = !first,
        )
        first = false
    }

    newMetadata.language?.takeIf { it.isNotBlank() }?.let { language ->
        SimpleFieldRow(
            label = stringResource(Res.string.metadata_field_language),
            value = language,
            isSelected = selections.language,
            onToggle = { onToggleField(MetadataField.LANGUAGE) },
            sourceLabel = fallbackSources[BookField.LANGUAGE],
            showDivider = !first,
        )
    }
}

/**
 * The accent-headed grouped section container used for the metadata field lists — an accent-tinted
 * [TonalIconTile] + UPPERCASE label floating above a [surfaceContainerLow] card.
 */
@Composable
private fun FieldGroup(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TonalIconTile(icon = icon, size = 30.dp, accent = accent)
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            content = { Column(content = content) },
        )
    }
}

/**
 * Emits the count-gated chapter-names row, if any.
 *
 * Renders nothing for [ChapterSuggestion.Unavailable], a disabled reason for
 * [ChapterSuggestion.CountMismatch], and a Review action for
 * [ChapterSuggestion.Available] that opens the per-chapter review sheet.
 */
private fun LazyListScope.chapterNamesSection(
    suggestion: ChapterSuggestion,
    onReviewChapters: () -> Unit,
) {
    if (suggestion is ChapterSuggestion.Unavailable) return
    item {
        ChapterNamesItem(suggestion = suggestion, onReview = onReviewChapters)
    }
}

/**
 * Count-gated chapter-name suggestion row body. Only [ChapterSuggestion.CountMismatch]
 * and [ChapterSuggestion.Available] reach here; [ChapterSuggestion.Unavailable] is
 * filtered out by [chapterNamesSection].
 */
@Composable
private fun ChapterNamesItem(
    suggestion: ChapterSuggestion,
    onReview: () -> Unit,
) {
    when (suggestion) {
        is ChapterSuggestion.Unavailable -> {
            return
        }

        is ChapterSuggestion.CountMismatch -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(Res.string.metadata_chapter_names),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text =
                            "${suggestion.audibleCount} Audible chapters → your ${suggestion.localCount} — " +
                                "different edition, unavailable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        is ChapterSuggestion.Available -> {
            Surface(
                onClick = onReview,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ScallopBadge(size = 52.dp, containerColor = MaterialTheme.colorScheme.secondary) {
                        Icon(
                            imageVector = Icons.Outlined.FormatListNumbered,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSecondary,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.metadata_chapters_matched, suggestion.rows.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(Res.string.metadata_review_and_apply_chapter_names),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = stringResource(Res.string.metadata_review),
                    )
                }
            }
        }
    }
}

/**
 * Region selector — a horizontally scrolling row of [PillChip]s for trying different Audible markets.
 */
@Composable
private fun RegionSelector(
    selectedRegion: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        MetadataLocale.SUPPORTED.forEach { region ->
            PillChip(
                label = region.displayName,
                onClick = { onRegionSelected(region) },
                selected = region == selectedRegion,
                leadingIcon = if (region == selectedRegion) Icons.Outlined.Check else null,
            )
        }
    }
}

/**
 * Cover field row: the leading [ExpressiveCheckbox] toggles whether a new cover is applied, beside a
 * "Cover" label and a horizontally scrollable strip of source options.
 */
@Composable
private fun CoverFieldRow(
    currentCoverPath: String?,
    coverOptions: List<CoverEntry>,
    isLoading: Boolean,
    selectedUrl: String?,
    isCoverEnabled: Boolean,
    onSelectCover: (String?) -> Unit,
    onToggleCover: () -> Unit,
    coverSourceLabel: String?,
    coverResolution: String?,
    showDivider: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FieldRowDivider(show = showDivider)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveCheckbox(checked = isCoverEnabled, onCheckedChange = { onToggleCover() })
            Column {
                Text(
                    text = stringResource(Res.string.metadata_cover),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (coverSourceLabel != null) {
                    Text(
                        text =
                            coverResolution?.let {
                                stringResource(Res.string.metadata_cover_source_resolution, coverSourceLabel, it)
                            } ?: coverSourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 15.dp),
        ) {
            if (currentCoverPath != null) {
                item {
                    CoverOptionCard(
                        label = stringResource(Res.string.metadata_current_cover),
                        source = null,
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

            items(coverOptions) { cover ->
                CoverOptionCard(
                    label = cover.label,
                    source = cover.label,
                    isSelected = selectedUrl == cover.url,
                    onClick = { onSelectCover(cover.url) },
                ) {
                    AsyncImage(
                        model = cover.url,
                        contentDescription = stringResource(Res.string.metadata_cover_from_source, cover.label),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            if (isLoading) {
                items(3) {
                    CoverOptionPlaceholder()
                }
            }
        }
    }
}

/**
 * Individual cover option card with image, source badge, and a selected indicator.
 */
@Composable
private fun CoverOptionCard(
    label: String,
    source: String?,
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
            shape = MaterialTheme.shapes.large,
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

                if (source != null) {
                    Surface(
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                if (isSelected) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(22.dp)
                                .clip(RoundedCornerShape(11.dp))
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

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
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
            shape = MaterialTheme.shapes.large,
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

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(Res.string.common_loading),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Inset divider between field rows inside a [FieldGroup] card (clears the leading checkbox column). */
@Composable
private fun FieldRowDivider(show: Boolean) {
    if (show) {
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Per-field provenance chip: "from iTunes", shown beside a field's label when the value was
 * sourced from a non-primary provider (a fallback fill). Distinct from the hero [SourceChip],
 * which always shows the primary matched-edition source regardless of per-field fallbacks.
 */
@Composable
private fun FieldSourceChip(label: String?) {
    if (label == null) return
    Text(
        text = stringResource(Res.string.metadata_field_source, label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(start = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * A simple selectable field row (title, subtitle, description, …): a leading [ExpressiveCheckbox], an
 * accent label, and the matched value. The whole row is the toggle target. [sourceLabel] renders a
 * [FieldSourceChip] beside the label when this field's value came from a fallback provider.
 */
@Composable
private fun SimpleFieldRow(
    label: String,
    value: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
    showDivider: Boolean,
    sourceLabel: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FieldRowDivider(show = showDivider)
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(15.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveCheckbox(checked = isSelected)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FieldSourceChip(sourceLabel)
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * Contributor (authors/narrators) field rows: an accent sub-label header followed by one
 * [ExpressiveCheckbox] row per contributor. [sourceLabel] renders a [FieldSourceChip] beside
 * the header when this field's values came from a fallback provider.
 */
@Composable
private fun ContributorFieldRows(
    label: String,
    contributors: List<MetadataContributorRef>,
    selectedAsins: Set<String>,
    onToggle: (String) -> Unit,
    showTopDivider: Boolean,
    sourceLabel: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FieldRowDivider(show = showTopDivider)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 56.dp, top = 15.dp, bottom = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            FieldSourceChip(sourceLabel)
        }
        contributors.forEach { contributor ->
            val asin = contributor.asin ?: contributor.name // Fallback to name if no ASIN
            ValueCheckRow(
                checked = asin in selectedAsins,
                text = contributor.name,
                onToggle = { onToggle(asin) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Series field rows: an accent sub-label header followed by one [ExpressiveCheckbox] row per series.
 * [sourceLabel] renders a [FieldSourceChip] beside the header when series came from a fallback provider.
 */
@Composable
private fun SeriesFieldRows(
    series: List<MetadataSeriesRef>,
    selectedAsins: Set<String>,
    onToggle: (String) -> Unit,
    showTopDivider: Boolean,
    sourceLabel: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FieldRowDivider(show = showTopDivider)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 56.dp, top = 15.dp, bottom = 4.dp),
        ) {
            Text(
                text = stringResource(Res.string.common_series),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            FieldSourceChip(sourceLabel)
        }
        series.forEach { seriesEntry ->
            val asin = seriesEntry.asin ?: seriesEntry.title // Fallback to title if no ASIN
            val displayText =
                if (seriesEntry.sequence != null) {
                    "${seriesEntry.title} #${seriesEntry.sequence}"
                } else {
                    seriesEntry.title
                }
            ValueCheckRow(
                checked = asin in selectedAsins,
                text = displayText,
                onToggle = { onToggle(asin) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** A single checkbox + value row used by the contributor and series field lists. */
@Composable
private fun ValueCheckRow(
    checked: Boolean,
    text: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ExpressiveCheckbox(checked = checked)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Genre field row: the matched genres as toggleable filled chips with a leading check when selected.
 * [sourceLabel] renders a [FieldSourceChip] beside the header when genres came from a fallback provider.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreFieldRow(
    genres: List<String>,
    selectedGenres: Set<String>,
    onToggle: (String) -> Unit,
    sourceLabel: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(
                text = stringResource(Res.string.metadata_field_genres),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            FieldSourceChip(sourceLabel)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            genres.forEach { genre ->
                GenreToggleChip(
                    label = genre,
                    selected = genre in selectedGenres,
                    onClick = { onToggle(genre) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodFieldRow(
    moods: List<String>,
    selectedMoods: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(Res.string.metadata_field_moods),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            moods.forEach { mood ->
                GenreToggleChip(
                    label = mood,
                    selected = mood in selectedMoods,
                    onClick = { onToggle(mood) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFieldRow(
    tags: List<String>,
    selectedTags: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(Res.string.metadata_field_tags),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                GenreToggleChip(
                    label = tag,
                    selected = tag in selectedTags,
                    onClick = { onToggle(tag) },
                )
            }
        }
    }
}

/** A toggleable genre chip — filled `tertiaryContainer` with a leading check when selected. */
@Composable
private fun GenreToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) colors.tertiaryContainer else colors.surfaceContainerHighest,
        contentColor = if (selected) colors.onTertiaryContainer else colors.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Message shown when the book is found on Audible but has no/minimal metadata.
 */
@Composable
private fun NoMetadataAvailableMessage(selectedRegion: MetadataLocale) {
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
