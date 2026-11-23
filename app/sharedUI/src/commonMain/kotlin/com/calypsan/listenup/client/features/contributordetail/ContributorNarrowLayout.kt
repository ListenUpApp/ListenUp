package com.calypsan.listenup.client.features.contributordetail

import com.calypsan.listenup.client.design.icons.ListenUpIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.contributoredit.components.ContributorColorScheme
import com.calypsan.listenup.client.features.contributoredit.components.rememberContributorColorScheme
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.RoleSection
import com.calypsan.listenup.client.presentation.contributordetail.formatLifeDates
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.BookProgress
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.contributor_aka
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.design.theme.ContentShapes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.HeroInk
// =============================================================================
// NARROW LAYOUT (immersive vertical with carousels)
// =============================================================================

@Composable
internal fun NarrowContributorPortfolio(
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
    val roleLabels = state.roleSections.map { roleChipLabel(it.role) }.distinct()
    val totalBooks = state.roleSections.sumOf { it.bookCount }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // 1. Color-blocked hero — avatar, role chips, name, aka, life dates.
        item {
            NarrowColorHero(
                state = state,
                contributorId = contributorId,
                colorScheme = colorScheme,
                roleLabels = roleLabels,
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onMatchDetails = onMatchDetails,
                onAdjustPhoto = onAdjustPhoto,
                onDeleteClick = onDeleteClick,
            )
        }

        // 2. Stat chip + About, on the surface below the hero.
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin, vertical = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroStatChip(
                        label = "$totalBooks ${if (totalBooks == 1) "book" else "books"}",
                        onColor = false,
                    )
                    if (state.totalDuration.inWholeMilliseconds > 0) {
                        HeroStatChip(
                            label = state.formatTotalDuration(),
                            onColor = false,
                            icon = ListenUpIcons.ScheduleFilled,
                        )
                    }
                }
                state.contributor.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(20.dp))
                    BiographySection(
                        description = description,
                        isExpanded = isDescriptionExpanded,
                        onToggleExpanded = { isDescriptionExpanded = !isDescriptionExpanded },
                    )
                }
            }
        }

        // 3. THE WORK - Role sections with carousels
        items(
            items = state.roleSections,
            key = { it.role },
        ) { section ->
            NarrowWorkSection(
                section = section,
                bookProgress = state.bookProgress,
                onBookClick = onBookClick,
                onViewAllClick = { onViewAllClick(section.role) },
            )
        }
    }
}

/** Color-blocked hero for the narrow layout: nav row, ringed scallop avatar, role chips, name, aka, dates. */
@Composable
private fun NarrowColorHero(
    state: ContributorDetailUiState.Ready,
    contributorId: String,
    colorScheme: ContributorColorScheme,
    roleLabels: List<String>,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onMatchDetails: () -> Unit,
    onAdjustPhoto: (() -> Unit)?,
    onDeleteClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(ContentShapes.hero)
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        HeroBlob(modifier = Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-50).dp).size(220.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            NavigationBar(
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onMatchDetails = onMatchDetails,
                onAdjustPhoto = onAdjustPhoto,
                onDeleteClick = onDeleteClick,
                canEditMetadata = state.canEditMetadata,
                canCurateLibrary = state.canCurateLibrary,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RingedScallopAvatar(
                    name = state.contributor.name,
                    imagePath = state.contributor.imagePath,
                    frame = state.contributor.imageFrame,
                    contributorId = contributorId,
                    colorScheme = colorScheme,
                )
                if (roleLabels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        roleLabels.forEach { RoleChip(it) }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.contributor.name,
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    color = ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                state.contributor.aliases.takeIf { it.isNotEmpty() }?.let { aliases ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(Res.string.contributor_aka, aliases.joinToString(", ")),
                        style = MaterialTheme.typography.titleMedium,
                        color = HeroInk.muted(),
                        textAlign = TextAlign.Center,
                    )
                }
                formatLifeDates(state.contributor.birthDate, state.contributor.deathDate)?.let { dates ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dates,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeroInk.muted(),
                        textAlign = TextAlign.Center,
                    )
                }
                // Website — the narrow hero previously omitted it, so a contributor's website never
                // showed on phones even when set. `ink` keeps it readable on the colour block.
                state.contributor.website?.takeIf { it.isNotBlank() }?.let { url ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Work section with horizontal carousel (narrow layout).
 */
@Composable
private fun NarrowWorkSection(
    section: RoleSection,
    bookProgress: Map<BookId, BookProgress.InProgress>,
    onBookClick: (String) -> Unit,
    onViewAllClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 12.dp),
    ) {
        WorkSectionHeader(
            section = section,
            onViewAllClick = onViewAllClick,
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        )

        Spacer(modifier = Modifier.height(16.dp))

        BrowseCarousel(
            items = section.previewBooks,
            itemWidth = 150.dp,
            itemSpacing = 16.dp,
            contentPadding = PaddingValues(horizontal = Spacing.screenMargin),
            key = { it.id.value },
        ) { book ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id.value) },
                duration = book.formatDuration(),
                progress = bookProgress[book.id],
                cardWidth = 150.dp,
            )
        }
    }
}
