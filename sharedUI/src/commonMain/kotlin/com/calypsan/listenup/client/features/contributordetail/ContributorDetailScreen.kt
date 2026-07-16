package com.calypsan.listenup.client.features.contributordetail

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.features.contributoredit.components.ContributorColorScheme
import com.calypsan.listenup.client.features.contributoredit.components.rememberContributorColorScheme
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailNavAction
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import com.calypsan.listenup.client.presentation.contributordetail.RoleSection
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.core.BookId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_more_options
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_edit
import listenup.composeapp.generated.resources.common_read_less
import listenup.composeapp.generated.resources.common_read_more
import listenup.composeapp.generated.resources.common_view_all
import listenup.composeapp.generated.resources.contributor_aka
import listenup.composeapp.generated.resources.contributor_download_metadata
import listenup.composeapp.generated.resources.contributor_from_your_library_this_action
import listenup.composeapp.generated.resources.contributor_name_profile_image
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Artist Portfolio screen - an immersive contributor detail experience.
 *
 * Adapts layout based on screen width:
 * - Narrow: immersive vertical layout with dramatic hero and horizontal carousels
 * - Wide: compact header row (avatar beside info) + book grids per role section
 */
@Composable
fun ContributorDetailScreen(
    contributorId: String,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onEditClick: (String) -> Unit,
    onViewAllClick: (contributorId: String, role: String) -> Unit,
    onMetadataClick: (String) -> Unit,
    viewModel: ContributorDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(contributorId) {
        viewModel.loadContributor(contributorId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    // Navigate back when a delete succeeds.
    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                ContributorDetailNavAction.Deleted -> onBackClick()
            }
        }
    }

    // Surface delete errors via snackbar.
    val deleteError = (state as? ContributorDetailUiState.Ready)?.deleteError
    LaunchedEffect(deleteError) {
        if (deleteError != null) {
            val friendlyMessage =
                when {
                    deleteError.contains("not found", ignoreCase = true) -> {
                        "Could not find metadata for this contributor on Audible"
                    }

                    deleteError.contains("network", ignoreCase = true) ||
                        deleteError.contains("connection", ignoreCase = true) -> {
                        "Network error. Please check your connection and try again."
                    }

                    else -> {
                        deleteError
                    }
                }
            snackbarHostState.showSnackbar(friendlyMessage)
            viewModel.dismissDeleteError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            ContributorDetailUiState.Idle, ContributorDetailUiState.Loading -> {
                ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is ContributorDetailUiState.Error -> {
                Text(
                    text = current.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            is ContributorDetailUiState.Ready -> {
                val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
                val useWideLayout =
                    windowSizeClass.isWidthAtLeastBreakpoint(
                        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                    )

                if (useWideLayout) {
                    WideContributorPortfolio(
                        contributorId = contributorId,
                        state = current,
                        onBackClick = onBackClick,
                        onEditClick = { onEditClick(contributorId) },
                        onDownloadMetadata = { onMetadataClick(contributorId) },
                        onDeleteClick = { showDeleteConfirmation = true },
                        onBookClick = onBookClick,
                        onViewAllClick = { role -> onViewAllClick(contributorId, role) },
                    )
                } else {
                    NarrowContributorPortfolio(
                        contributorId = contributorId,
                        state = current,
                        onBackClick = onBackClick,
                        onEditClick = { onEditClick(contributorId) },
                        onDownloadMetadata = { onMetadataClick(contributorId) },
                        onDeleteClick = { showDeleteConfirmation = true },
                        onBookClick = onBookClick,
                        onViewAllClick = { role -> onViewAllClick(contributorId, role) },
                    )
                }

                if (showDeleteConfirmation) {
                    ListenUpDestructiveDialog(
                        onDismissRequest = { showDeleteConfirmation = false },
                        title = stringResource(Res.string.common_delete_name, "Contributor"),
                        text =
                            "This will remove ${current.contributor.name} " +
                                stringResource(Res.string.contributor_from_your_library_this_action),
                        confirmText = stringResource(Res.string.common_delete),
                        onConfirm = {
                            showDeleteConfirmation = false
                            viewModel.confirmDelete()
                        },
                    )
                }

                if (current.isDeleting) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ListenUpLoadingIndicator()
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// =============================================================================
// WIDE LAYOUT (header row + book grids)
// =============================================================================

@Composable
private fun WideContributorPortfolio(
    contributorId: String,
    state: ContributorDetailUiState.Ready,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onDownloadMetadata: () -> Unit,
    onDeleteClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onViewAllClick: (role: String) -> Unit,
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    val colorScheme = rememberContributorColorScheme(contributorId)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
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
                onDownloadMetadata = onDownloadMetadata,
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
    onDownloadMetadata: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val contributor = state.contributor
    val roleLabels = state.roleSections.map { roleChipLabel(it.role) }.distinct()
    val totalBooks = state.roleSections.sumOf { it.bookCount }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        HeroBlob(modifier = Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-60).dp).size(240.dp))
        Column(modifier = Modifier.padding(24.dp)) {
            // Navigation bar — the wide hero is an already-inset rounded panel (padding(24.dp) inside
            // a clipped card), so it must NOT re-apply the status-bar inset or it gains dead space.
            NavigationBar(
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onDownloadMetadata = onDownloadMetadata,
                onDeleteClick = onDeleteClick,
                surfaceColor = MaterialTheme.colorScheme.surface,
                applyStatusBarInset = false,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Avatar + Info row
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RingedScallopAvatar(
                    name = contributor.name,
                    imagePath = contributor.imagePath,
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
    birthDate: String?,
    deathDate: String?,
    website: String?,
    description: String?,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer

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
    )

    // Aliases
    if (aliases.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.contributor_aka, aliases.joinToString(", ")),
            style = MaterialTheme.typography.bodyLarge,
            color = ink.copy(alpha = 0.85f),
        )
    }

    // Life dates
    val lifeDates = formatLifeDates(birthDate, deathDate)
    if (lifeDates != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = lifeDates,
            style = MaterialTheme.typography.bodyLarge,
            color = ink.copy(alpha = 0.7f),
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

    // Stat chip
    Spacer(modifier = Modifier.height(16.dp))
    HeroStatChip(label = "$totalBooks ${if (totalBooks == 1) "book" else "books"}", onColor = true)

    // Biography
    description?.takeIf { it.isNotBlank() }?.let { desc ->
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyLarge,
            color = ink.copy(alpha = 0.85f),
            maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (desc.length > 200) {
            TextButton(
                onClick = onToggleDescription,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(if (isDescriptionExpanded) Res.string.common_read_less else Res.string.common_read_more))
            }
        }
    }
}

/**
 * Section header for a work/role section. Shared between layouts.
 */
@Composable
private fun WorkSectionHeader(
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
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(stringResource(Res.string.common_view_all))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// =============================================================================
// NARROW LAYOUT (immersive vertical with carousels)
// =============================================================================

@Composable
private fun NarrowContributorPortfolio(
    contributorId: String,
    state: ContributorDetailUiState.Ready,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onDownloadMetadata: () -> Unit,
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
                onDownloadMetadata = onDownloadMetadata,
                onDeleteClick = onDeleteClick,
            )
        }

        // 2. Stat chip + About, on the surface below the hero.
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                HeroStatChip(
                    label = "$totalBooks ${if (totalBooks == 1) "book" else "books"}",
                    onColor = false,
                )
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
    onDownloadMetadata: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        HeroBlob(modifier = Modifier.align(Alignment.TopEnd).offset(x = 70.dp, y = (-50).dp).size(220.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            NavigationBar(
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onDownloadMetadata = onDownloadMetadata,
                onDeleteClick = onDeleteClick,
                surfaceColor = MaterialTheme.colorScheme.surface,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RingedScallopAvatar(
                    name = state.contributor.name,
                    imagePath = state.contributor.imagePath,
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
                )
                state.contributor.aliases.takeIf { it.isNotEmpty() }?.let { aliases ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(Res.string.contributor_aka, aliases.joinToString(", ")),
                        style = MaterialTheme.typography.titleMedium,
                        color = ink.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                }
                formatLifeDates(state.contributor.birthDate, state.contributor.deathDate)?.let { dates ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dates,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink.copy(alpha = 0.7f),
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
    bookProgress: Map<BookId, Float>,
    onBookClick: (String) -> Unit,
    onViewAllClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 12.dp),
    ) {
        WorkSectionHeader(
            section = section,
            onViewAllClick = onViewAllClick,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        BrowseCarousel(
            items = section.previewBooks,
            itemWidth = 150.dp,
            itemSpacing = 16.dp,
            contentPadding = PaddingValues(horizontal = 24.dp),
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

// =============================================================================
// SHARED COMPONENTS
// =============================================================================

/**
 * Floating back button and overflow menu.
 */
@Composable
private fun NavigationBar(
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onDownloadMetadata: () -> Unit,
    onDeleteClick: () -> Unit,
    surfaceColor: Color,
    applyStatusBarInset: Boolean = true,
) {
    HeroNavRow(
        onBack = onBackClick,
        buttonBackground = surfaceColor.copy(alpha = 0.5f),
        applyStatusBarInset = applyStatusBarInset,
    ) {
        if (!LocalDeviceContext.current.isLeanback) {
            OverflowMenu(
                onEditClick = onEditClick,
                onDownloadMetadata = onDownloadMetadata,
                onDeleteClick = onDeleteClick,
                surfaceColor = surfaceColor,
            )
        }
    }
}

/**
 * Overflow menu (edit / download metadata / delete) shared by both hero navigation rows.
 */
@Composable
private fun OverflowMenu(
    onEditClick: () -> Unit,
    onDownloadMetadata: () -> Unit,
    onDeleteClick: () -> Unit,
    surfaceColor: Color,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { showMenu = true },
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(Res.string.book_detail_more_options),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_edit)) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = {
                    showMenu = false
                    onEditClick()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.contributor_download_metadata)) },
                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                onClick = {
                    showMenu = false
                    onDownloadMetadata()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(Res.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showMenu = false
                    onDeleteClick()
                },
            )
        }
    }
}

/**
 * Elevated avatar (140dp) with contributor image or initials.
 *
 * Hero call sites pass the M3 Expressive cookie [shape]; smaller inline uses keep the default circle.
 */
@Composable
private fun ElevatedAvatar(
    name: String,
    imagePath: String?,
    contributorId: String,
    colorScheme: ContributorColorScheme,
    shape: Shape = CircleShape,
    elevation: Dp = 12.dp,
) {
    val initials =
        if (name.isNotBlank()) {
            name
                .trim()
                .split("\\s+".toRegex())
                .let { parts ->
                    when {
                        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                        name.length >= 2 -> name.take(2)
                        else -> name.take(1)
                    }
                }.uppercase()
        } else {
            "?"
        }

    ElevatedCard(
        shape = shape,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = colorScheme.primary,
            ),
        modifier = Modifier.size(140.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            var imageLoaded by remember(contributorId) { mutableStateOf(false) }

            if (!imageLoaded) {
                Text(
                    text = initials,
                    style =
                        MaterialTheme.typography.displayMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                    color = colorScheme.onPrimary,
                )
            }

            com.calypsan.listenup.client.design.components.ContributorCoverImage(
                contributorId = contributorId,
                imagePath = imagePath,
                contentDescription = stringResource(Res.string.contributor_name_profile_image, name),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is coil3.compose.AsyncImagePainter.State.Success) {
                        imageLoaded = true
                    }
                },
            )
        }
    }
}

@Composable
private fun BiographySection(
    description: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.common_about),
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )

        if (description.length > 200) {
            TextButton(
                onClick = onToggleExpanded,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(if (isExpanded) Res.string.common_read_less else Res.string.common_read_more))
            }
        }
    }
}

/** Scallop avatar with a brand-colored rim, matching the design's "cookie" avatar. */
@Composable
private fun RingedScallopAvatar(
    name: String,
    imagePath: String?,
    contributorId: String,
    colorScheme: ContributorColorScheme,
) {
    Box(
        modifier =
            Modifier
                .size(152.dp)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedAvatar(
            name = name,
            imagePath = imagePath,
            contributorId = contributorId,
            colorScheme = colorScheme,
            shape = cookieScallopShape(),
            elevation = 0.dp,
        )
    }
}

/** Small role pill (Author/Narrator), tinted per role to match the design. */
@Composable
private fun RoleChip(label: String) {
    val narrator = label.equals("Narrator", ignoreCase = true)
    val bg = if (narrator) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (narrator) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Box(
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** Book-count stat pill. [onColor] tints it for placement on the color-blocked hero. */
@Composable
private fun HeroStatChip(
    label: String,
    onColor: Boolean,
) {
    val bg =
        if (onColor) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val fg = if (onColor) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** Soft organic accent blob behind the hero content (echoes the design's brand squircle). */
@Composable
private fun HeroBlob(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(BlobShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
    )
}

/** Asymmetric rounded squircle approximating the design's organic blob. */
private val BlobShape =
    RoundedCornerShape(
        topStartPercent = 46,
        topEndPercent = 54,
        bottomEndPercent = 46,
        bottomStartPercent = 54,
    )

// =============================================================================
// HELPERS
// =============================================================================

/** Short role label for a hero chip (e.g. "author" -> "Author"). */
private fun roleChipLabel(role: String): String =
    when (role.lowercase()) {
        "author" -> "Author"
        "narrator" -> "Narrator"
        "translator" -> "Translator"
        "editor" -> "Editor"
        else -> role.replaceFirstChar { it.uppercase() }
    }

private fun formatLifeDates(
    birthDate: String?,
    deathDate: String?,
): String? {
    val birth = birthDate?.let { formatDateForDisplay(it) }
    val death = deathDate?.let { formatDateForDisplay(it) }

    return when {
        birth != null && death != null -> "$birth – $death"
        birth != null -> "Born $birth"
        death != null -> "Died $death"
        else -> null
    }
}

private fun formatDateForDisplay(isoDate: String): String? {
    return try {
        val parts = isoDate.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month < 1 || month > 12) return null
        val epochMillis =
            LocalDate(year, month, day)
                .atStartOfDayIn(TimeZone.UTC)
                .toEpochMilliseconds()
        formatDateLong(epochMillis)
    } catch (
        @Suppress("SwallowedException") e: Exception,
    ) {
        null
    }
}
