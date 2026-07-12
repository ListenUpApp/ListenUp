package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.mostSpecificGenre
import com.calypsan.listenup.client.features.bookdetail.BookDetailScanWarning
import com.calypsan.listenup.client.features.bookdetail.SupplementaryMaterialsSection
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_show_all_chapters
import org.jetbrains.compose.resources.stringResource

private const val CHAPTER_PREVIEW_LIMIT = 10

/** Right-column card max width — fluid below this, capped above it (adaptive-but-responsive rule). */
private val RIGHT_COLUMN_MAX_WIDTH = 420.dp

/**
 * Wide (tablet / desktop) Book Detail layout on the Material 3 Expressive "Color Block" design.
 *
 * A plain [BookDetailTopBar] is hoisted above the scroll; the scrolling [Column] then carries, in
 * order: an optional [OfflineBanner] and [BookDetailScanWarning] advisory, the full-width
 * [WideHeroBand], a [StatsRow], a two-column [Row] (left: About card + connected
 * [PrimaryActionsSection]; right: Readers card + Chapters card).
 *
 * The left column flexes with `weight(1f)`; the right column caps at [RIGHT_COLUMN_MAX_WIDTH] so it
 * stays readable on very wide displays while still flexing on smaller medium-width screens.
 */
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
    documents: List<BookDocument> = emptyList(),
    onOpenDocument: (docId: String) -> Unit = {},
    showPlaybackActions: Boolean = true,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onMarkNotStartedClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit = {},
    campfireLiveCount: Int = 0,
    onCampfireClick: () -> Unit = {},
    onDeleteBookClick: () -> Unit,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    playEnabled: Boolean = true,
    downloadEnabled: Boolean = true,
    showServerWarning: Boolean = false,
    onRetryConnection: () -> Unit,
    onPlayDisabledClick: () -> Unit = {},
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit = {},
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var isChaptersExpanded by rememberSaveable { mutableStateOf(false) }
    // Full-cast overlay target — set by a folded hero author/narrator line, cleared on dismiss.
    var castRole by remember { mutableStateOf<CastRole?>(null) }

    val book = state.book
    val screenPadding = Modifier.padding(horizontal = Spacing.screenMargin)
    // Hero classification: the most-specific genre as a chip beside the Abridged/Unabridged flag
    // (mirrors the compact hero).
    val heroGenre = book.mostSpecificGenre()?.name

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BookDetailTopBar(
            title = book.title,
            isComplete = isComplete,
            hasProgress = hasProgress,
            isAdmin = isAdmin,
            onBackClick = onBackClick,
            onEditClick = onEditClick,
            onFindMetadataClick = onFindMetadataClick,
            onMarkCompleteClick = onMarkCompleteClick,
            onMarkNotStartedClick = onMarkNotStartedClick,
            onAddToShelfClick = onAddToShelfClick,
            onAddToCollectionClick = onAddToCollectionClick,
            onShareClick = onShareClick,
            campfireLiveCount = campfireLiveCount,
            onCampfireClick = onCampfireClick,
            onDeleteClick = onDeleteBookClick,
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
        ) {
            // Offline advisory — streaming unavailable, downloads still play.
            if (showServerWarning) {
                OfflineBanner(
                    onRetryClick = onRetryConnection,
                    compact = false,
                    modifier = screenPadding.padding(top = 8.dp),
                )
            }

            // Scan-warning advisory — heads-up when the scanner flagged this book's files.
            BookDetailScanWarning(
                hasScanWarning = state.hasScanWarning,
                modifier = screenPadding.padding(vertical = 8.dp),
            )

            // Identity — full-width color band: title, independent subtitle, series chips, talent,
            // and the stat chips as the last element of the identity column.
            WideHeroBand(
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                bookId = bookId,
                title = book.title,
                genre = heroGenre,
                abridged = book.abridged,
                subtitle = state.subtitle,
                series = book.series,
                authors = book.authors,
                narrators = book.narrators,
                onContributorClick = onContributorClick,
                onSeriesClick = onSeriesClick,
                onShowCast = { castRole = it },
                progress = state.progress,
                timeRemaining = state.timeRemainingFormatted,
                rating = state.rating,
                duration = book.duration,
                year = state.year,
                addedAt = state.addedAt,
                modifier = screenPadding.padding(top = 8.dp),
            )

            // Two-column body.
            WideBodyColumns(
                bookId = bookId,
                state = state,
                downloadStatus = downloadStatus,
                isWaitingForWifi = isWaitingForWifi,
                showPlaybackActions = showPlaybackActions,
                playEnabled = playEnabled,
                downloadEnabled = downloadEnabled,
                showServerWarning = showServerWarning,
                isDescriptionExpanded = isDescriptionExpanded,
                onToggleDescription = { isDescriptionExpanded = !isDescriptionExpanded },
                documents = documents,
                onOpenDocument = onOpenDocument,
                isChaptersExpanded = isChaptersExpanded,
                onExpandChapters = { isChaptersExpanded = true },
                onContributorClick = onContributorClick,
                onTagClick = onTagClick,
                onMoodClick = onMoodClick,
                onPlayClick = onPlayClick,
                onDownloadClick = onDownloadClick,
                onCancelClick = onCancelClick,
                onDeleteClick = onDeleteClick,
                onPlayDisabledClick = onPlayDisabledClick,
                onUserProfileClick = onUserProfileClick,
                onSeeAllReaders = onSeeAllReaders,
                modifier = screenPadding.fillMaxWidth().padding(top = 24.dp),
            )
        }

        castRole?.let { role ->
            FullCastSheetFor(
                role = role,
                authors = book.authors,
                narrators = book.narrators,
                onContributorClick = onContributorClick,
                onDismiss = { castRole = null },
            )
        }
    }
}

/**
 * The two-column wide body: the flexing left column (About + primary actions) and the
 * width-capped right column (Readers + Chapters + Supplementary materials). Extracted from
 * [WideBookDetail] to keep that composable within the method-length budget.
 */
@Suppress("LongParameterList")
@Composable
private fun WideBodyColumns(
    bookId: String,
    state: BookDetailUiState.Ready,
    downloadStatus: BookDownloadStatus,
    isWaitingForWifi: Boolean,
    showPlaybackActions: Boolean,
    playEnabled: Boolean,
    downloadEnabled: Boolean,
    showServerWarning: Boolean,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    documents: List<BookDocument>,
    onOpenDocument: (docId: String) -> Unit,
    isChaptersExpanded: Boolean,
    onExpandChapters: () -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onPlayDisabledClick: () -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        WideLeftColumn(
            state = state,
            downloadStatus = downloadStatus,
            isWaitingForWifi = isWaitingForWifi,
            showPlaybackActions = showPlaybackActions,
            playEnabled = playEnabled,
            downloadEnabled = downloadEnabled,
            showServerWarning = showServerWarning,
            isDescriptionExpanded = isDescriptionExpanded,
            onToggleDescription = onToggleDescription,
            onContributorClick = onContributorClick,
            onTagClick = onTagClick,
            onMoodClick = onMoodClick,
            onPlayClick = onPlayClick,
            onDownloadClick = onDownloadClick,
            onCancelClick = onCancelClick,
            onDeleteClick = onDeleteClick,
            onPlayDisabledClick = onPlayDisabledClick,
            modifier = Modifier.weight(1f),
        )

        WideRightColumn(
            bookId = bookId,
            chapters = state.chapters,
            documents = documents,
            onOpenDocument = onOpenDocument,
            isChaptersExpanded = isChaptersExpanded,
            onExpandChapters = onExpandChapters,
            onUserProfileClick = onUserProfileClick,
            onSeeAllReaders = onSeeAllReaders,
            modifier = Modifier.widthIn(max = RIGHT_COLUMN_MAX_WIDTH),
        )
    }
}

/**
 * Left column of the wide body: the grouped About card (description + Genres + Tags), a
 * [DetailsSection] (publisher / published / language / format + credits), and the connected
 * Play + Download action group.
 */
@Suppress("LongParameterList")
@Composable
private fun WideLeftColumn(
    state: BookDetailUiState.Ready,
    downloadStatus: BookDownloadStatus,
    isWaitingForWifi: Boolean,
    showPlaybackActions: Boolean,
    playEnabled: Boolean,
    downloadEnabled: Boolean,
    showServerWarning: Boolean,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onPlayDisabledClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = state.book

    Column(modifier = modifier) {
        // Primary actions — connected Play + Download group, kept above the description so the
        // primary action is reachable without scrolling on short foldable inner displays.
        if (showPlaybackActions) {
            PrimaryActionsSection(
                downloadStatus = downloadStatus,
                onPlayClick = onPlayClick,
                onDownloadClick = onDownloadClick,
                onCancelClick = onCancelClick,
                onDeleteClick = onDeleteClick,
                modifier = Modifier.padding(bottom = 16.dp),
                isWaitingForWifi = isWaitingForWifi,
                playEnabled = playEnabled,
                downloadEnabled = downloadEnabled,
                onPlayDisabledClick = onPlayDisabledClick,
                showServerWarning = showServerWarning,
            )
        }

        // About — description + Genres + Tags, framed in a surfaceContainerLow card.
        AboutSection(
            description = state.descriptionText,
            genres = state.genresList,
            tags = state.tags,
            moods = state.moods,
            isLoadingTags = state.isLoadingTags,
            isCard = true,
            isDescriptionExpanded = isDescriptionExpanded,
            onToggleDescriptionExpanded = onToggleDescription,
            onGenreClick = null,
            onTagClick = { tag -> onTagClick(tag.id, tag.displayName()) },
            onMoodClick = { mood -> onMoodClick(mood.id, mood.displayName()) },
            modifier = Modifier.fillMaxWidth(),
            creditsSlot = null,
        )

        // Details — publisher / published / language / format, then contributor credits.
        DetailsSection(
            publisher = book.publisher,
            publishYear = book.publishYear,
            language = book.language,
            audioFiles = book.audioFiles,
            credits = book.allContributors,
            onContributorClick = onContributorClick,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * Right column of the wide body: the Readers card and the Chapters card, each wrapped in a
 * [MaterialTheme.colorScheme.surfaceContainerLow] [Surface] with [ContentShapes.card] shape and
 * [Spacing.screenMargin] inner padding — mirroring [AboutSection]'s card treatment.
 */
@Composable
private fun WideRightColumn(
    bookId: String,
    chapters: List<ChapterUiModel>,
    documents: List<BookDocument>,
    onOpenDocument: (docId: String) -> Unit,
    isChaptersExpanded: Boolean,
    onExpandChapters: () -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Readers card — social reading activity. Self-cards only when populated, so no
        // hollow surface is drawn on the common no-readers / Loading / Error paths.
        BookReadersSection(
            bookId = bookId,
            onUserClick = onUserProfileClick,
            isCard = true,
            onSeeAllClick = onSeeAllReaders,
            modifier = Modifier.fillMaxWidth(),
        )

        // Chapters card — header + (optionally collapsed) chapter rows + "show all" affordance.
        WideSectionCard {
            WideChaptersContent(
                chapters = chapters,
                isExpanded = isChaptersExpanded,
                onExpand = onExpandChapters,
            )
        }

        // Supplementary materials card — PDFs and other documents attached to this book.
        // Mirrors the phone layout's placement (after chapters).
        if (documents.isNotEmpty()) {
            WideSectionCard {
                SupplementaryMaterialsSection(
                    documents = documents,
                    onOpenDocument = onOpenDocument,
                )
            }
        }
    }
}

/**
 * Card shell for a right-column section — [surfaceContainerLow] background, [ContentShapes.card]
 * shape, [Spacing.screenMargin] inner padding. Matches [AboutSection]'s card treatment so the
 * left and right columns read as one design language.
 */
@Composable
private fun WideSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ContentShapes.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(Spacing.screenMargin)) {
            content()
        }
    }
}

/** Chapters header, the (optionally collapsed) chapter rows, and the "show all" affordance. */
@Composable
private fun WideChaptersContent(
    chapters: List<ChapterUiModel>,
    isExpanded: Boolean,
    onExpand: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ChaptersHeader(
            chapterCount = chapters.size,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        val displayedChapters = if (isExpanded) chapters else chapters.take(CHAPTER_PREVIEW_LIMIT)
        displayedChapters.forEachIndexed { index, chapter ->
            ChapterListItem(
                chapter = chapter,
                chapterNumber = index + 1,
                modifier = Modifier.padding(horizontal = 8.dp),
                // TODO(book-detail): mark current chapter once progress→chapter mapping is available.
                isCurrent = false,
                showDivider = index < displayedChapters.lastIndex,
            )
        }

        if (chapters.size > CHAPTER_PREVIEW_LIMIT && !isExpanded) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(
                    onClick = onExpand,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(stringResource(Res.string.book_show_all_chapters, chapters.size))
                }
            }
        }
    }
}
