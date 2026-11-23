package com.calypsan.listenup.client.features.contributordetail

import com.calypsan.listenup.client.design.icons.ListenUpIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.contributoredit.components.ContributorColorScheme
import com.calypsan.listenup.client.features.contributoredit.components.rememberContributorColorScheme
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.RoleSection
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.common_read_less
import listenup.composeapp.generated.resources.common_read_more
import listenup.composeapp.generated.resources.common_view_all
import listenup.composeapp.generated.resources.contributor_aka
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.design.theme.ContentShapes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.HeroInk
// =============================================================================
// WIDE LAYOUT (header row + book grids)
// =============================================================================

@Composable
internal fun WideContributorPortfolio(
    contributorId: String,
    state: ContributorDetailUiState.Ready,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onMatchDetails: () -> Unit,
    onAdjustPhoto: (() -> Unit)?,
    onDeleteClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onViewAllClick: (role: String) -> Unit,
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    val colorScheme = rememberContributorColorScheme(contributorId)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(horizontal = Spacing.screenMargin, vertical = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Full-span header: avatar beside name, metadata, and bio
        item(span = { GridItemSpan(maxLineSpan) }) {
            WideHeroHeader(
                state = state,
                contributorId = contributorId,
                colorScheme = colorScheme,
                isDescriptionExpanded = isDescriptionExpanded,
                onToggleDescription = { isDescriptionExpanded = !isDescriptionExpanded },
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onMatchDetails = onMatchDetails,
                onAdjustPhoto = onAdjustPhoto,
                onDeleteClick = onDeleteClick,
            )
        }

        // Role sections with grid items
        for (section in state.roleSections) {
            // Full-span section header
            item(
                key = "header_${section.role}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                WorkSectionHeader(
                    section = section,
                    onViewAllClick = { onViewAllClick(section.role) },
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }

            // Grid of book cards for this section
            items(
                items = section.previewBooks,
                key = { "${section.role}_${it.id.value}" },
            ) { book ->
                BookCard(
                    cover = book.toCoverModel(),
                    onClick = { onBookClick(book.id.value) },
                    duration = book.formatDuration(),
                    progress = state.bookProgress[book.id],
                )
            }
        }
    }
}

/**
 * Compact hero header for wide layout.
 * Avatar on the left, name/metadata/bio on the right, with subtle gradient.
 */
@Composable
private fun WideHeroHeader(
    state: ContributorDetailUiState.Ready,
    contributorId: String,
    colorScheme: ContributorColorScheme,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onMatchDetails: () -> Unit,
    onAdjustPhoto: (() -> Unit)?,
    onDeleteClick: () -> Unit,
) {
    val contributor = state.contributor
    val roleLabels = state.roleSections.map { roleChipLabel(it.role) }.distinct()
    val totalBooks = state.roleSections.sumOf { it.bookCount }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        HeroBlob(modifier = Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-60).dp).size(240.dp))
        Column(modifier = Modifier.padding(Spacing.xl)) {
            // Navigation bar — the wide hero is an already-inset rounded panel (padding(24.dp) inside
            // a clipped card), so it must NOT re-apply the status-bar inset or it gains dead space.
            NavigationBar(
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onMatchDetails = onMatchDetails,
                onAdjustPhoto = onAdjustPhoto,
                onDeleteClick = onDeleteClick,
                canEditMetadata = state.canEditMetadata,
                canCurateLibrary = state.canCurateLibrary,
                applyStatusBarInset = false,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Avatar + Info row
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                verticalAlignment = Alignment.Top,
            ) {
                RingedScallopAvatar(
                    name = contributor.name,
                    imagePath = contributor.imagePath,
                    frame = contributor.imageFrame,
                    contributorId = contributorId,
                    colorScheme = colorScheme,
                )

                // Name, aliases, metadata, bio
                Column(modifier = Modifier.weight(1f)) {
                    WideHeroInfoColumn(
                        name = contributor.name,
                        aliases = contributor.aliases,
                        roleLabels = roleLabels,
                        totalBooks = totalBooks,
                        totalDuration =
                            state.formatTotalDuration().takeIf {
                                state.totalDuration.inWholeMilliseconds > 0
                            },
                        birthDate = contributor.birthDate,
                        deathDate = contributor.deathDate,
                        website = contributor.website,
                        description = contributor.description,
                        isDescriptionExpanded = isDescriptionExpanded,
                        onToggleDescription = onToggleDescription,
                    )
                }
            }
        }
    }
}

/**
 * Role chips, name, aliases, life dates, website, stat chip, and biography for the wide hero —
 * rendered on the color-blocked panel (on-primary-container ink).
 */
@Composable
private fun ColumnScope.WideHeroInfoColumn(
    name: String,
    aliases: List<String>,
    roleLabels: List<String>,
    totalBooks: Int,
    totalDuration: String?,
    birthDate: String?,
    deathDate: String?,
    website: String?,
    description: String?,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val haptics = LocalHaptics.current

    // Role chips
    if (roleLabels.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            roleLabels.forEach { RoleChip(it) }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }

    // Name
    Text(
        text = name,
        style = MaterialTheme.typography.headlineLargeEmphasized,
        color = ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading() },
    )

    // Aliases
    if (aliases.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.contributor_aka, aliases.joinToString(", ")),
            style = MaterialTheme.typography.bodyLarge,
            color = HeroInk.muted(),
        )
    }

    // Life dates
    val lifeDates = formatLifeDates(birthDate, deathDate)
    if (lifeDates != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = lifeDates,
            style = MaterialTheme.typography.bodyLarge,
            color = HeroInk.muted(),
        )
    }

    // Website
    website?.takeIf { it.isNotBlank() }?.let { url ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = url,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    // Stat chips
    Spacer(modifier = Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroStatChip(label = "$totalBooks ${if (totalBooks == 1) "book" else "books"}", onColor = true)
        if (totalDuration != null) {
            HeroStatChip(label = totalDuration, onColor = true, icon = ListenUpIcons.ScheduleFilled)
        }
    }

    // Biography
    description?.takeIf { it.isNotBlank() }?.let { desc ->
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyLarge,
            color = HeroInk.muted(),
            maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (desc.length > 200) {
            TextButton(
                onClick = {
                    haptics.press()
                    onToggleDescription()
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    stringResource(
                        if (isDescriptionExpanded) Res.string.common_read_less else Res.string.common_read_more,
                    ),
                )
            }
        }
    }
}

/**
 * Section header for a work/role section. Shared between layouts.
 */
@Composable
internal fun WorkSectionHeader(
    section: RoleSection,
    onViewAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = section.displayName,
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    if (section.bookCount == 1) {
                        stringResource(Res.string.common_book_count, section.bookCount)
                    } else {
                        stringResource(Res.string.common_books_count, section.bookCount)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (section.showViewAll) {
            FilledTonalButton(
                onClick = onViewAllClick,
                shape = ContentShapes.card,
            ) {
                Text(stringResource(Res.string.common_view_all))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = ListenUpIcons.ArrowForwardFilled,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
