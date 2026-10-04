package com.calypsan.listenup.client.features.metadata

import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.SectionColumns
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
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
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.features.metadata.components.RegionSelector
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
import listenup.composeapp.generated.resources.metadata_chip_from_source
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
import com.calypsan.listenup.client.design.theme.HeroInk
import com.calypsan.listenup.client.design.util.isLargeFontScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.calypsan.listenup.client.design.components.rememberHeroScrollBehavior

private const val DESCRIPTION_PREVIEW_LIMIT = 200

/**
 * Full-screen preview of metadata changes before applying.
 *
 * Shows all available metadata fields with checkboxes so users can
 * select which fields to apply. Supports region selection for
 * trying different Audible markets.
 *
 * A phone stacks the matched edition, the region and the field sections in one list above an apply
 * bar. From the expanded width the matched edition, its region and the apply action become a side
 * panel — what is being applied stays next to the button that applies it — and the field sections
 * flow into [SectionColumns] beside it, so identity and classification are read side by side.
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
    appliedCover: CoverEntry?,
    onSelectCover: (String) -> Unit,
    onKeepCurrentCover: () -> Unit,
    // Chapter names
    chapterSuggestion: ChapterSuggestion,
    onReviewChapters: () -> Unit,
    // Provenance
    fallbackSources: Map<BookField, String>,
    genreSources: Map<String, String> = emptyMap(),
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
    val panelBeside =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )
    // Identity is always present, so an empty list means exactly "the match carries no data".
    val sections =
        if (newMetadata.hasAnyData()) {
            metadataFieldSections(
                currentBook = currentBook,
                newMetadata = newMetadata,
                selections = selections,
                coverOptions = coverOptions,
                isLoadingCovers = isLoadingCovers,
                appliedCover = appliedCover,
                onSelectCover = onSelectCover,
                onKeepCurrentCover = onKeepCurrentCover,
                chapterSuggestion = chapterSuggestion,
                onReviewChapters = onReviewChapters,
                fallbackSources = fallbackSources,
                genreSources = genreSources,
                onToggleField = onToggleField,
                onToggleAuthor = onToggleAuthor,
                onToggleNarrator = onToggleNarrator,
                onToggleSeries = onToggleSeries,
                onToggleGenre = onToggleGenre,
                onToggleMood = onToggleMood,
                onToggleTag = onToggleTag,
            )
        } else {
            emptyList()
        }
    // At a large font the pinned apply bar keeps only the button: where the fields were merged from moves to
    // the head of the list, so the bar doesn't take the room the fields are reviewed in.
    val mergedFromInList = !panelBeside && isLargeFontScale()
    val applyActions: @Composable (Modifier) -> Unit = { modifier ->
        ApplyActions(
            applyError = applyError,
            isApplying = isApplying,
            hasAnySelected = hasAnySelected,
            contributingSources = if (mergedFromInList) emptyList() else contributingSources,
            onApply = onApply,
            modifier = modifier,
        )
    }

    // At a large font the hero slides away as the fields scroll, rather than holding a third of the screen.
    val heroScroll = rememberHeroScrollBehavior()
    ListenUpScaffold(
        modifier = heroScroll?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier,
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.metadata_select_metadata),
                badgeIcon = Icons.AutoMirrored.Outlined.MenuBook,
                onBack = onBack,
                scrollBehavior = heroScroll,
            )
        },
        bottomBar = {
            if (!panelBeside) {
                Surface(tonalElevation = 3.dp) {
                    applyActions(Modifier.padding(18.dp))
                }
            }
        },
    ) { padding ->
        val hero: @Composable () -> Unit = {
            MatchedEditionHero(
                match = newMetadata,
                coverUrl = newMetadata.coverUrl,
                selectedRegion = selectedRegion,
            )
        }
        val regionPicker: @Composable () -> Unit = {
            RegionPicker(selectedRegion = selectedRegion, onRegionSelected = onRegionSelected)
        }
        val noDataMessage: @Composable () -> Unit = {
            NoMatchDataMessage(previewNotFound = previewNotFound, selectedRegion = selectedRegion)
        }
        if (panelBeside) {
            MatchPreviewWideLayout(
                padding = padding,
                hero = hero,
                regionPicker = regionPicker,
                applyActions = { applyActions(Modifier) },
                sections = sections,
                noDataMessage = noDataMessage,
            )
        } else {
            MatchPreviewPhoneList(
                padding = padding,
                mergedFrom = if (mergedFromInList) contributingSources else emptyList(),
                hero = hero,
                regionPicker = regionPicker,
                sections = sections,
                noDataMessage = noDataMessage,
            )
        }
    }
}

/**
 * The expanded layout: the matched edition, its region and the apply action in a side panel; the
 * field [sections] as [SectionColumns] beside it, or [noDataMessage] when the match has none.
 */
@Suppress("LongParameterList")
@Composable
private fun MatchPreviewWideLayout(
    padding: PaddingValues,
    hero: @Composable () -> Unit,
    regionPicker: @Composable () -> Unit,
    applyActions: @Composable () -> Unit,
    sections: List<@Composable () -> Unit>,
    noDataMessage: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        Column(
            modifier =
                Modifier
                    .width(MatchPanelWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            hero()
            regionPicker()
            applyActions()
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 20.dp, bottom = 16.dp),
        ) {
            if (sections.isEmpty()) {
                noDataMessage()
            } else {
                SectionColumns { sections.forEach { section(it) } }
            }
        }
    }
}

/** The phone layout: hero, region, then one list item per field section (or [noDataMessage]). */
@Composable
private fun MatchPreviewPhoneList(
    padding: PaddingValues,
    mergedFrom: List<String>,
    hero: @Composable () -> Unit,
    regionPicker: @Composable () -> Unit,
    sections: List<@Composable () -> Unit>,
    noDataMessage: @Composable () -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (mergedFrom.size > 1) item { MergedFromLine(mergedFrom) }
        item { hero() }
        item { regionPicker() }
        if (sections.isEmpty()) {
            item { noDataMessage() }
        } else {
            sections.forEach { section -> item { section() } }
        }
    }
}

/** Width of the expanded layout's match panel — one comfortable card column. */
private val MatchPanelWidth = 360.dp

/** The Audible-region overline over its [RegionSelector]. */
@Composable
private fun RegionPicker(
    selectedRegion: MetadataLocale,
    onRegionSelected: (MetadataLocale) -> Unit,
) {
    Column {
        Overline(text = stringResource(Res.string.metadata_audible_region))
        Spacer(modifier = Modifier.height(12.dp))
        RegionSelector(
            selectedRegion = selectedRegion,
            onRegionSelected = onRegionSelected,
        )
    }
}

/** Why there are no field sections: the match was not found in this region, or nothing would change. */
@Composable
private fun NoMatchDataMessage(
    previewNotFound: Boolean,
    selectedRegion: MetadataLocale,
) {
    if (previewNotFound) {
        NoMetadataAvailableMessage(selectedRegion = selectedRegion)
    } else {
        AlreadyUpToDateMessage()
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
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
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
                        tint = HeroInk.muted(),
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
                        color = HeroInk.muted(),
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
        shape = CircleShape,
        color = HeroInk.wash(),
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

/** The provenance line, the apply error if any, and the apply button — the phone's bottom bar, or the foot of the wide panel. */
@Composable
private fun ApplyActions(
    applyError: String?,
    isApplying: Boolean,
    hasAnySelected: Boolean,
    contributingSources: List<String>,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (contributingSources.size > 1) {
            MergedFromLine(contributingSources, modifier = Modifier.padding(bottom = 8.dp))
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

/** "Merged from Audible, Hardcover, iTunes": which providers the preview's fields came from. */
@Composable
private fun MergedFromLine(
    sources: List<String>,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(Res.string.metadata_merged_from, sources.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The field sections that apply — identity always, classification, details and chapter names when the
 * match carries them — in reading order, each a self-contained block. The phone emits one list item
 * per section; the wide layout lays the same blocks out as [SectionColumns].
 *
 * The callback set is fanned straight to each row, so a parameter object would only add an
 * indirection layer Compose tooling discourages.
 */
@Suppress("LongParameterList")
private fun metadataFieldSections(
    currentBook: BookDetail,
    newMetadata: MetadataBook,
    selections: MetadataSelections,
    coverOptions: List<CoverEntry>,
    isLoadingCovers: Boolean,
    appliedCover: CoverEntry?,
    onSelectCover: (String) -> Unit,
    onKeepCurrentCover: () -> Unit,
    chapterSuggestion: ChapterSuggestion,
    onReviewChapters: () -> Unit,
    fallbackSources: Map<BookField, String>,
    genreSources: Map<String, String>,
    onToggleField: (MetadataField) -> Unit,
    onToggleAuthor: (String) -> Unit,
    onToggleNarrator: (String) -> Unit,
    onToggleSeries: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onToggleMood: (String) -> Unit,
    onToggleTag: (String) -> Unit,
): List<@Composable () -> Unit> =
    buildList {
        // ── IDENTITY ──
        add {
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
                    appliedCover = appliedCover,
                    onSelectCover = onSelectCover,
                    onKeepCurrentCover = onKeepCurrentCover,
                    fallbackSources = fallbackSources,
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
            add {
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
                            genreSources = genreSources,
                        )
                    }
                    if (newMetadata.moods.isNotEmpty()) {
                        MoodFieldRow(
                            moods = newMetadata.moods,
                            selectedMoods = selections.selectedMoods,
                            onToggle = onToggleMood,
                            sourceLabel = fallbackSources[BookField.MOODS],
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
            add {
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

        // Chapter names (count-gated; no section when unavailable)
        if (chapterSuggestion !is ChapterSuggestion.Unavailable) {
            add { ChapterNamesItem(suggestion = chapterSuggestion, onReview = onReviewChapters) }
        }
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
    appliedCover: CoverEntry?,
    onSelectCover: (String) -> Unit,
    onKeepCurrentCover: () -> Unit,
    fallbackSources: Map<BookField, String>,
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
        appliedCover = appliedCover,
        isCoverEnabled = selections.cover,
        onSelectCover = onSelectCover,
        onKeepCurrentCover = onKeepCurrentCover,
        onToggleCover = { onToggleField(MetadataField.COVER) },
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
                modifier = Modifier.semantics { heading() },
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
 * Count-gated chapter-name suggestion row body: a disabled reason for
 * [ChapterSuggestion.CountMismatch], and a Review action for [ChapterSuggestion.Available]
 * that opens the per-chapter review sheet. [ChapterSuggestion.Unavailable] never reaches
 * here; [metadataFieldSections] adds no section for it.
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
                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
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
            val haptics = LocalHaptics.current
            Surface(
                onClick = {
                    haptics.press()
                    onReview()
                },
                modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
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
                            color =
                                HeroInk.muted(
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                ),
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
 * Cover field row: the leading [ExpressiveCheckbox] toggles whether a new cover is applied, beside a
 * "Cover" label naming the source of the cover Apply will write, and a horizontally scrollable strip
 * of the choices — "Current cover" first, then every candidate. Exactly one tile is ever selected,
 * and it is [appliedCover] (or the current cover when that is null): the tile you see chosen is the
 * cover Apply writes.
 */
@Composable
private fun CoverFieldRow(
    currentCoverPath: String?,
    coverOptions: List<CoverEntry>,
    isLoading: Boolean,
    appliedCover: CoverEntry?,
    isCoverEnabled: Boolean,
    onSelectCover: (String) -> Unit,
    onKeepCurrentCover: () -> Unit,
    onToggleCover: () -> Unit,
    showDivider: Boolean,
) {
    val haptics = LocalHaptics.current
    Column(modifier = Modifier.fillMaxWidth()) {
        FieldRowDivider(show = showDivider)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, top = 15.dp)
                    // The box and its "Cover" label are one checkbox, so TalkBack names what it applies.
                    .toggleable(value = isCoverEnabled, role = Role.Checkbox) { on ->
                        haptics.toggle(on = on)
                        onToggleCover()
                    },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveCheckbox(checked = isCoverEnabled)
            Column {
                Text(
                    text = stringResource(Res.string.metadata_cover),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val resolution = appliedCover?.resolution
                Text(
                    text =
                        when {
                            appliedCover == null -> {
                                stringResource(Res.string.metadata_current_cover)
                            }

                            resolution != null -> {
                                stringResource(
                                    Res.string.metadata_cover_source_resolution,
                                    appliedCover.label,
                                    resolution,
                                )
                            }

                            else -> {
                                appliedCover.label
                            }
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = 15.dp),
        ) {
            // Always offered, even for a book with no artwork yet: keeping what the book has is a choice.
            item {
                CoverOptionCard(
                    label = stringResource(Res.string.metadata_current_cover),
                    source = null,
                    isSelected = appliedCover == null,
                    onClick = onKeepCurrentCover,
                ) {
                    ListenUpAsyncImage(
                        path = currentCoverPath,
                        contentDescription = stringResource(Res.string.metadata_current_cover),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            items(coverOptions) { cover ->
                CoverOptionCard(
                    label = cover.label,
                    source = cover.label,
                    isSelected = appliedCover?.url == cover.url,
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
    val haptics = LocalHaptics.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier =
                Modifier
                    .size(100.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        haptics.selectionTick()
                        onClick()
                    },
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
                        shape = MaterialTheme.shapes.extraSmall,
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
                                .clip(CircleShape)
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
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
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
    val haptics = LocalHaptics.current
    Column(modifier = Modifier.fillMaxWidth()) {
        FieldRowDivider(show = showDivider)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = isSelected, role = Role.Checkbox) { on ->
                        haptics.toggle(on = on)
                        onToggle()
                    }.padding(15.dp),
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
    val haptics = LocalHaptics.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox) { on ->
                    haptics.toggle(on = on)
                    onToggle()
                }.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
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
 * Genres a gap-filling source (Hardcover) added are grouped after the match's own under their own
 * [FieldSourceChip] (#1542); [sourceLabel] marks the header when the match's own genres came from a fallback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreFieldRow(
    genres: List<String>,
    selectedGenres: Set<String>,
    onToggle: (String) -> Unit,
    sourceLabel: String? = null,
    genreSources: Map<String, String> = emptyMap(),
) {
    val runs = genres.groupBy { genreSources[it] }
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(
                text = stringResource(Res.string.metadata_field_genres),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.semantics { heading() },
            )
            FieldSourceChip(sourceLabel?.takeIf { null in runs })
        }
        runs.forEach { (source, run) ->
            if (source != null) {
                Row(modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)) { FieldSourceChip(source) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                run.forEach { genre ->
                    GenreToggleChip(
                        label = genre,
                        selected = genre in selectedGenres,
                        onClick = { onToggle(genre) },
                        source = source ?: sourceLabel,
                    )
                }
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
    sourceLabel: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(
                text = stringResource(Res.string.metadata_field_moods),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.semantics { heading() },
            )
            FieldSourceChip(sourceLabel)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            moods.forEach { mood ->
                GenreToggleChip(
                    label = mood,
                    selected = mood in selectedMoods,
                    onClick = { onToggle(mood) },
                    source = sourceLabel,
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
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Text(
            text = stringResource(Res.string.metadata_field_tags),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
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

/**
 * A toggleable genre chip — filled `tertiaryContainer` with a leading check when selected. A chip a fallback
 * provider supplied names its [source] itself ("Funny, from Hardcover"), so it isn't heard apart from the
 * "from Hardcover" chip that heads its run.
 */
@Composable
private fun GenreToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    source: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHaptics.current
    val spoken = source?.let { stringResource(Res.string.metadata_chip_from_source, label, it) }
    Surface(
        selected = selected,
        onClick = {
            haptics.selectionTick()
            onClick()
        },
        modifier =
            Modifier.semantics {
                role = Role.Checkbox
                spoken?.let { contentDescription = it }
            },
        shape = CircleShape,
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
            imageVector = Icons.Outlined.Warning,
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
            imageVector = Icons.Outlined.CheckCircle,
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
