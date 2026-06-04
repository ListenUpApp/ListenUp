
package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.ElevatedCoverCard
import com.calypsan.listenup.client.design.components.ProgressOverlay
import com.calypsan.listenup.client.design.components.rememberCoverColors
import com.calypsan.listenup.client.design.theme.LocalDarkTheme
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.BookDetailScanWarning
import com.calypsan.listenup.client.features.bookdetail.TagsSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_server_is_unreachable_connect_to
import org.jetbrains.compose.resources.stringResource

/**
 * Wide book detail layout for tablets, foldables, desktop, and TV.
 * Single unified LazyColumn with a horizontal hero Row at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
fun WideBookDetail(
    bookId: String,
    state: BookDetailUiState.Ready,
    downloadStatus: BookDownloadStatus,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    isWaitingForWifi: Boolean,
    showPlaybackActions: Boolean = true,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onDiscardProgressClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit = {},
    onDeleteBookClick: () -> Unit,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    playEnabled: Boolean = true,
    downloadEnabled: Boolean = true,
    showServerWarning: Boolean = false,
    onPlayDisabledClick: () -> Unit = {},
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
) {
    val deviceContext = LocalDeviceContext.current
    val book = state.book
    val coverColors =
        rememberCoverColors(
            imagePath = book.coverPath,
            cachedDominantColor = book.dominantColor,
            cachedDarkMutedColor = book.darkMutedColor,
            cachedVibrantColor = book.vibrantColor,
        )
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = LocalDarkTheme.current

    val gradientColors =
        if (isDark) {
            listOf(
                coverColors.darkMuted.copy(alpha = 0.5f),
                coverColors.darkMuted.copy(alpha = 0.35f),
                coverColors.darkMuted.copy(alpha = 0.2f),
                surfaceColor,
            )
        } else {
            listOf(
                coverColors.darkMuted.copy(alpha = 0.95f),
                coverColors.darkMuted.copy(alpha = 0.85f),
                coverColors.darkMuted.copy(alpha = 0.7f),
                surfaceColor,
            )
        }

    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var isChaptersExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top app bar
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                WideBookDetailAppBarActions(
                    deviceContext = deviceContext,
                    isComplete = isComplete,
                    hasProgress = hasProgress,
                    isAdmin = isAdmin,
                    onShareClick = onShareClick,
                    onEditClick = onEditClick,
                    onFindMetadataClick = onFindMetadataClick,
                    onMarkCompleteClick = onMarkCompleteClick,
                    onDiscardProgressClick = onDiscardProgressClick,
                    onAddToShelfClick = onAddToShelfClick,
                    onAddToCollectionClick = onAddToCollectionClick,
                    onDeleteBookClick = onDeleteBookClick,
                )
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // Hero section with gradient background
            bookDetailHeroItem(
                state = state,
                gradientColors = gradientColors,
                downloadStatus = downloadStatus,
                isWaitingForWifi = isWaitingForWifi,
                showPlaybackActions = showPlaybackActions,
                playEnabled = playEnabled,
                downloadEnabled = downloadEnabled,
                onContributorClick = onContributorClick,
                onPlayClick = onPlayClick,
                onDownloadClick = onDownloadClick,
                onCancelClick = onCancelClick,
                onDeleteClick = onDeleteClick,
                onPlayDisabledClick = onPlayDisabledClick,
            )

            bookDetailContentItems(
                bookId = bookId,
                state = state,
                showServerWarning = showServerWarning,
                isDescriptionExpanded = isDescriptionExpanded,
                onToggleDescription = { isDescriptionExpanded = !isDescriptionExpanded },
                onSeriesClick = onSeriesClick,
                onTagClick = onTagClick,
                onUserProfileClick = onUserProfileClick,
            )

            // Chapters
            bookDetailChaptersItems(
                chapters = state.chapters,
                isChaptersExpanded = isChaptersExpanded,
                onExpandChapters = { isChaptersExpanded = true },
            )
        }
    }
}

private const val CHAPTER_PREVIEW_LIMIT = 10

/**
 * Content below the hero: scan/server advisories, description, context metadata, tags, and readers.
 * Authored as a [LazyListScope] extension so item order and conditional presence stay byte-identical
 * to the inline emissions they replaced.
 */
@Suppress("LongParameterList")
private fun LazyListScope.bookDetailContentItems(
    bookId: String,
    state: BookDetailUiState.Ready,
    showServerWarning: Boolean,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onTagClick: (tagId: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
) {
    val book = state.book

    // Scan-warning advisory — heads-up when the scanner flagged this book's files.
    item {
        BookDetailScanWarning(
            hasScanWarning = state.hasScanWarning,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
    }

    // Server unreachable warning — informational only, does not disable buttons
    if (showServerWarning) {
        item { ServerUnreachableWarning() }
    }

    // Content below hero — standard surface background
    // Description
    state.description.takeIf { it.isNotBlank() }?.let { description ->
        item {
            DescriptionSection(
                description = description,
                isExpanded = isDescriptionExpanded,
                onToggleExpanded = onToggleDescription,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
        }
    }

    // Context metadata
    item {
        ContextMetadataSection(
            seriesId = book.seriesId,
            seriesName = state.series ?: book.seriesName,
            rating = state.rating,
            duration = book.duration,
            year = state.year,
            addedAt = state.addedAt,
            genres = state.genresList,
            onSeriesClick = onSeriesClick,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
        )
    }

    // Tags
    if (state.tags.isNotEmpty()) {
        item {
            TagsSection(
                tags = state.tags,
                isLoading = state.isLoadingTags,
                onTagClick = { tag -> onTagClick(tag.id) },
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
        }
    }

    // Readers
    item {
        BookReadersSection(
            bookId = bookId,
            onUserClick = onUserProfileClick,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
        )
    }
}

/**
 * Chapter section: header, the (optionally collapsed) chapter rows, and a "show all" affordance.
 * Authored as a [LazyListScope] extension so item order and key identity stay byte-identical to
 * the inline emission they replaced.
 */
private fun LazyListScope.bookDetailChaptersItems(
    chapters: List<ChapterUiModel>,
    isChaptersExpanded: Boolean,
    onExpandChapters: () -> Unit,
) {
    item {
        ChaptersHeader(
            chapterCount = chapters.size,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
        )
    }

    val displayedChapters = if (isChaptersExpanded) chapters else chapters.take(CHAPTER_PREVIEW_LIMIT)
    itemsIndexed(
        items = displayedChapters,
        key = { _, chapter -> chapter.id },
    ) { index, chapter ->
        ChapterListItemCompact(
            chapter = chapter,
            chapterNumber = index + 1,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }

    if (chapters.size > CHAPTER_PREVIEW_LIMIT && !isChaptersExpanded) {
        item {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TextButton(onClick = onExpandChapters) {
                    Text("Show all ${chapters.size} chapters")
                }
            }
        }
    }
}

/**
 * Hero header: gradient backdrop, cover with progress overlay, and the title/talent/actions column.
 * Authored as a [LazyListScope] extension so it stays the first item in the list, identical to the
 * inline `item { }` it replaced.
 */
@Suppress("LongParameterList")
private fun LazyListScope.bookDetailHeroItem(
    state: BookDetailUiState.Ready,
    gradientColors: List<Color>,
    downloadStatus: BookDownloadStatus,
    isWaitingForWifi: Boolean,
    showPlaybackActions: Boolean,
    playEnabled: Boolean,
    downloadEnabled: Boolean,
    onContributorClick: (contributorId: String) -> Unit,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onPlayDisabledClick: () -> Unit,
) {
    item {
        val book = state.book
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(gradientColors))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Cover
                ElevatedCoverCard(
                    path = book.coverPath,
                    bookId = book.id.value,
                    blurHash = book.coverBlurHash,
                    contentDescription = book.title,
                    modifier =
                        Modifier
                            .width(300.dp)
                            .aspectRatio(1f),
                ) {
                    state.progress?.let { progress ->
                        ProgressOverlay(
                            progress = progress,
                            timeRemaining = state.timeRemainingFormatted,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                // Metadata + actions
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Top: metadata
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Title
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // Subtitle
                        state.subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Talent
                        TalentSectionWithRoles(
                            authors = book.authors,
                            narrators = book.narrators,
                            allContributors = book.allContributors,
                            onContributorClick = onContributorClick,
                            horizontalAlignment = Alignment.Start,
                        )
                    }

                    // Bottom: actions (flush with cover bottom)
                    if (showPlaybackActions) {
                        PrimaryActionsSection(
                            downloadStatus = downloadStatus,
                            onPlayClick = onPlayClick,
                            onDownloadClick = onDownloadClick,
                            onCancelClick = onCancelClick,
                            onDeleteClick = onDeleteClick,
                            modifier = Modifier.widthIn(max = 400.dp),
                            isWaitingForWifi = isWaitingForWifi,
                            playEnabled = playEnabled,
                            downloadEnabled = downloadEnabled,
                            onPlayDisabledClick = onPlayDisabledClick,
                            requestFocus = LocalDeviceContext.current.hasDpad,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterListItemCompact(
    chapter: ChapterUiModel,
    chapterNumber: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapterNumber.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 20.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = chapter.duration,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerUnreachableWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(Res.string.book_detail_server_is_unreachable_connect_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun WideBookDetailAppBarActions(
    deviceContext: com.calypsan.listenup.client.device.DeviceContext,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onDiscardProgressClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onDeleteBookClick: () -> Unit,
) {
    if (!deviceContext.isLeanback) {
        IconButton(onClick = onShareClick) {
            Icon(Icons.Default.Share, contentDescription = "Share")
        }
    }
    if (deviceContext.canEdit) {
        var showMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            BookActionsMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                isComplete = isComplete,
                hasProgress = hasProgress,
                isAdmin = isAdmin,
                onEditClick = {
                    showMenu = false
                    onEditClick()
                },
                onFindMetadataClick = {
                    showMenu = false
                    onFindMetadataClick()
                },
                onMarkCompleteClick = {
                    showMenu = false
                    onMarkCompleteClick()
                },
                onDiscardProgressClick = {
                    showMenu = false
                    onDiscardProgressClick()
                },
                onAddToShelfClick = {
                    showMenu = false
                    onAddToShelfClick()
                },
                onAddToCollectionClick = {
                    showMenu = false
                    onAddToCollectionClick()
                },
                onShareClick = {
                    showMenu = false
                    onShareClick()
                },
                onDeleteClick = {
                    showMenu = false
                    onDeleteBookClick()
                },
            )
        }
    }
}
