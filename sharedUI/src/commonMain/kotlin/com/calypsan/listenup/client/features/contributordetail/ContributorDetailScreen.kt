@file:Suppress("CognitiveComplexMethod")

package com.calypsan.listenup.client.features.contributordetail

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.design.components.DetailHero
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.contributorAvatarShape

import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.features.contributoredit.components.ContributorColorScheme
import com.calypsan.listenup.client.features.contributoredit.components.rememberContributorColorScheme
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailNavAction
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import com.calypsan.listenup.client.presentation.contributordetail.RoleSection
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.book_detail_more_options
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.contributor_download_metadata
import listenup.composeapp.generated.resources.common_edit
import listenup.composeapp.generated.resources.contributor_from_your_library_this_action
import listenup.composeapp.generated.resources.contributor_name_profile_image
import listenup.composeapp.generated.resources.common_view_all
import com.calypsan.listenup.core.Success

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
                name = state.contributor.name,
                aliases = state.contributor.aliases,
                imagePath = state.contributor.imagePath,
                contributorId = contributorId,
                colorScheme = colorScheme,
                birthDate = state.contributor.birthDate,
                deathDate = state.contributor.deathDate,
                website = state.contributor.website,
                description = state.contributor.description,
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
                    bookId = book.id.value,
                    title = book.title,
                    coverPath = book.coverPath,
                    blurHash = book.coverBlurHash,
                    onClick = { onBookClick(book.id.value) },
                    authorName = book.authorNames,
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
@Suppress("CognitiveComplexMethod")
private fun WideHeroHeader(
    name: String,
    aliases: List<String>,
    imagePath: String?,
    contributorId: String,
    colorScheme: ContributorColorScheme,
    birthDate: String?,
    deathDate: String?,
    website: String?,
    description: String?,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onDownloadMetadata: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val gradientColors =
        listOf(
            colorScheme.primaryDark.copy(alpha = 0.3f),
            colorScheme.primaryMuted.copy(alpha = 0.15f),
            surfaceColor,
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(gradientColors),
                    RoundedCornerShape(16.dp),
                ).padding(24.dp),
    ) {
        Column {
            // Navigation bar
            NavigationBar(
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onDownloadMetadata = onDownloadMetadata,
                onDeleteClick = onDeleteClick,
                surfaceColor = surfaceColor,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Avatar + Info row
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Avatar
                ElevatedAvatar(
                    name = name,
                    imagePath = imagePath,
                    contributorId = contributorId,
                    colorScheme = colorScheme,
                    shape = contributorAvatarShape(),
                )

                // Name, aliases, metadata, bio
                Column(modifier = Modifier.weight(1f)) {
                    // Name
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Aliases
                    if (aliases.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "aka ${aliases.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Life dates
                    val lifeDates = formatLifeDates(birthDate, deathDate)
                    if (lifeDates != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = lifeDates,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                    // Biography
                    description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (desc.length > 200) {
                            TextButton(
                                onClick = onToggleDescription,
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(if (isDescriptionExpanded) "Read less" else "Read more")
                            }
                        }
                    }
                }
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
                text = "${section.bookCount} ${if (section.bookCount == 1) "book" else "books"}",
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
    val surfaceColor = MaterialTheme.colorScheme.surface

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val collapseThresholdPx = with(density) { 220.dp.toPx() }
    val collapseFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / collapseThresholdPx).coerceIn(0f, 1f)
            }
        }
    }

    val gradientColors =
        listOf(
            colorScheme.primaryDark,
            colorScheme.primaryMuted.copy(alpha = 0.8f),
            colorScheme.primaryMuted.copy(alpha = 0.4f),
            surfaceColor.copy(alpha = 0.9f),
            surfaceColor,
        )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // 1. HERO SECTION — meta + bio ride in the hero's belowTitle slot.
        item {
            DetailHero(
                collapseFraction = { collapseFraction },
                collapsing = true,
                gradientColors = gradientColors,
                navigation = { pinnedTitle ->
                    HeroNavigationRow(
                        pinnedTitle = pinnedTitle,
                        onBackClick = onBackClick,
                        onEditClick = onEditClick,
                        onDownloadMetadata = onDownloadMetadata,
                        onDeleteClick = onDeleteClick,
                        surfaceColor = surfaceColor,
                    )
                },
                title = state.contributor.name,
                backdropMedia = {
                    ElevatedAvatar(
                        name = state.contributor.name,
                        imagePath = state.contributor.imagePath,
                        contributorId = contributorId,
                        colorScheme = colorScheme,
                        shape = contributorAvatarShape(),
                    )
                },
                subtitle =
                    state.contributor.aliases
                        .takeIf { it.isNotEmpty() }
                        ?.let { "aka ${it.joinToString(", ")}" },
                belowTitle = {
                    ArtistMetadata(
                        birthDate = state.contributor.birthDate,
                        deathDate = state.contributor.deathDate,
                        website = state.contributor.website,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    state.contributor.description?.takeIf { it.isNotBlank() }?.let { description ->
                        BiographySection(
                            description = description,
                            isExpanded = isDescriptionExpanded,
                            onToggleExpanded = { isDescriptionExpanded = !isDescriptionExpanded },
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                },
            )
        }

        // 2. THE WORK - Role sections with carousels
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

/**
 * Hero navigation row for the narrow [DetailHero]: back button on the left, the collapse-driven
 * [pinnedTitle] centered, and the overflow menu on the right.
 */
@Composable
private fun HeroNavigationRow(
    pinnedTitle: @Composable () -> Unit,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onDownloadMetadata: () -> Unit,
    onDeleteClick: () -> Unit,
    surfaceColor: Color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            pinnedTitle()
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = onBackClick,
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(
                            color = surfaceColor.copy(alpha = 0.5f),
                            shape = CircleShape,
                        ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

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

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(
                items = section.previewBooks,
                key = { it.id.value },
            ) { book ->
                BookCard(
                    bookId = book.id.value,
                    title = book.title,
                    coverPath = book.coverPath,
                    blurHash = book.coverBlurHash,
                    onClick = { onBookClick(book.id.value) },
                    authorName = book.authorNames,
                    duration = book.formatDuration(),
                    progress = bookProgress[book.id],
                    cardWidth = 150.dp,
                )
            }
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
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = onBackClick,
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

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
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp),
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
private fun ArtistMetadata(
    birthDate: String?,
    deathDate: String?,
    website: String?,
    modifier: Modifier = Modifier,
) {
    val hasContent = birthDate != null || deathDate != null || website?.isNotBlank() == true

    if (!hasContent) return

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val lifeDates = formatLifeDates(birthDate, deathDate)
        if (lifeDates != null) {
            Text(
                text = lifeDates,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        website?.takeIf { it.isNotBlank() }?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
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
                Text(if (isExpanded) "Read less" else "Read more")
            }
        }
    }
}

// =============================================================================
// HELPERS
// =============================================================================

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
        val monthNames =
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            )
        "${monthNames[month - 1]} $day, $year"
    } catch (
        @Suppress("SwallowedException") e: Exception,
    ) {
        null
    }
}
