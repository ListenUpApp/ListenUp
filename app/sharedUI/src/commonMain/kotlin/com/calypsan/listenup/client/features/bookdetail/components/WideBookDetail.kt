package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.features.bookdetail.BookDetailActions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.features.bookdetail.BookDetailScanWarning
import com.calypsan.listenup.client.features.bookdetail.SupplementaryMaterialsSection
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_show_all_chapters
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder

private const val CHAPTER_PREVIEW_LIMIT = 10

/** Right-column card max width — fluid below this, capped above it (adaptive-but-responsive rule). */
private val RIGHT_COLUMN_MAX_WIDTH = 420.dp

/**
 * Wide (tablet / desktop) Book Detail layout on the Material 3 Expressive "Color Block" design.
 *
 * A plain, untitled [BookDetailTopBar] is hoisted above the scroll; the scrolling [Column] then carries, in
 * order: an optional [OfflineBanner] and [BookDetailScanWarning] advisory, the [WideHeroBand] (identity, then the
 * [ResumeUnit] and a labelled [ListenActionGroup] inside it, spec §3.2.4), and a two-column [Row] (left: the held
 * triage panel when held, About, Visibility, Details; right: Ratings, Readers, Hardcover, Chapters).
 *
 * The left column flexes with `weight(1f)`; the right column caps at [RIGHT_COLUMN_MAX_WIDTH] so it
 * stays readable on very wide displays while still flexing on smaller medium-width screens.
 */
@Composable
fun WideBookDetail(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument> = emptyList(),
    isPreparing: Boolean = false,
    /** Book Detail's last-match row, under the actions in the left column. */
    lastMatchRow: @Composable (Modifier) -> Unit = {},
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var isChaptersExpanded by rememberSaveable { mutableStateOf(false) }
    // Full-cast overlay target — set by a folded hero author/narrator line, cleared on dismiss.
    var castRole by remember { mutableStateOf<CastRole?>(null) }

    val book = state.book
    val screenPadding = Modifier.padding(horizontal = Spacing.screenMargin)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BookDetailTopBar(
            isComplete = state.isComplete,
            hasProgress = state.progress != null,
            isAdmin = state.isAdmin,
            canEditMetadata = state.canEditMetadata,
            onBackClick = actions.onBackClick,
            onEditClick = actions.onEditClick,
            onFindMetadataClick = actions.onFindMetadataClick,
            onEditChaptersClick = actions.onEditChaptersClick,
            onMarkCompleteClick = actions.onMarkCompleteClick,
            onMarkNotStartedClick = actions.onMarkNotStartedClick,
            onRestartClick = actions.onRestartClick,
            onAddToCollectionClick = actions.onAddToCollectionClick,
            onShareClick = actions.onShareClick,
            onDeleteClick = actions.onDeleteBookClick,
            showActions = !state.isHeld,
        )

        // The viewport is measured so the chapter pane can be given a finite height: it is a lazy
        // list nested in this scroll, and a lazy list asked to be as tall as it likes has no height
        // to lay out in.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 32.dp),
            ) {
                // Offline advisory — streaming unavailable, downloads still play.
                if (state.showServerWarning) {
                    OfflineBanner(
                        onRetryClick = actions.onRetryConnection,
                        compact = false,
                        modifier = screenPadding.padding(top = 8.dp),
                    )
                }

                // Scan-warning advisory — heads-up when the scanner flagged this book's files.
                BookDetailScanWarning(
                    hasScanWarning = state.hasScanWarning,
                    modifier = screenPadding.padding(vertical = 8.dp),
                )

                // Identity, then the Resume unit and the action group inside the hero (spec §3.2.4).
                WideHero(
                    bookId = bookId,
                    state = state,
                    actions = actions,
                    isPreparing = isPreparing,
                    onShowCast = { castRole = it },
                )

                // Two-column body.
                WideBodyColumns(
                    bookId = bookId,
                    state = state,
                    actions = actions,
                    isDescriptionExpanded = isDescriptionExpanded,
                    onToggleDescription = { isDescriptionExpanded = !isDescriptionExpanded },
                    documents = documents,
                    isChaptersExpanded = isChaptersExpanded,
                    onExpandChapters = { isChaptersExpanded = true },
                    lastMatchRow = lastMatchRow,
                    chapterPaneMaxHeight = viewportHeight,
                    modifier = screenPadding.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }

        castRole?.let { role ->
            FullCastSheetFor(
                role = role,
                authors = book.authors,
                narrators = book.narrators,
                onContributorClick = actions.onContributorClick,
                onDismiss = { castRole = null },
                everyone = book.allContributors,
            )
        }
    }
}

/**
 * The wide hero (spec §3.2.4): identity, then the Resume unit and the labelled action group inside it, unless the
 * book is held (the triage panel replaces the action unit) or playback is unavailable here.
 */
@Composable
private fun WideHero(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    isPreparing: Boolean,
    onShowCast: (CastRole) -> Unit,
) {
    val book = state.book
    val showListen = !state.isHeld && state.isPlaybackAvailable
    WideHeroBand(
        coverPath = book.coverPath,
        coverHash = book.coverHash,
        bookId = bookId,
        title = book.title,
        subtitle = state.subtitle,
        seriesPaths = state.seriesPaths,
        authors = book.authors,
        narrators = book.narrators,
        durationMs = book.duration,
        year = state.year,
        abridged = book.abridged,
        onContributorClick = actions.onContributorClick,
        onSeriesClick = actions.onSeriesClick,
        onShowCast = onShowCast,
        resumeUnit = {
            if (showListen) {
                ResumeUnit(
                    readingStage = state.readingStage,
                    currentChapter = state.currentChapter,
                    timeRemainingMs = state.timeRemainingMs,
                    progress = state.progress,
                    finishedSpan = state.finishedSpan,
                )
            }
        },
        actions = {
            if (showListen) {
                ListenActionGroup(
                    primaryAction = state.primaryAction,
                    isPreparing = isPreparing,
                    downloadStatus = state.downloadStatus,
                    isWaitingForWifi = state.isWaitingForWifi,
                    downloadSizeBytes = state.downloadSizeBytes,
                    supportsDownloads = LocalDeviceContext.current.supportsDownloads,
                    canDownload = state.canDownload,
                    labelled = true,
                    onPrimaryClick = actions.onPlayClick,
                    onDownload = actions.onDownloadClick,
                    onCancelDownload = actions.onCancelClick,
                    onRemoveDownload = actions.onDeleteClick,
                    onAddToShelf = actions.onAddToShelfClick,
                )
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * The two-column wide body: the flexing left column (About, Visibility, Details) and the
 * width-capped right column (Readers + Chapters + Supplementary materials). Extracted from
 * [WideBookDetail] to keep that composable within the method-length budget.
 */
@Composable
private fun WideBodyColumns(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    documents: List<BookDocument>,
    isChaptersExpanded: Boolean,
    onExpandChapters: () -> Unit,
    lastMatchRow: @Composable (Modifier) -> Unit,
    chapterPaneMaxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        WideLeftColumn(
            state = state,
            actions = actions,
            isDescriptionExpanded = isDescriptionExpanded,
            onToggleDescription = onToggleDescription,
            lastMatchRow = lastMatchRow,
            modifier = Modifier.weight(1f),
        )

        WideRightColumn(
            bookId = bookId,
            isHeld = state.isHeld,
            chapters = state.chapters,
            documents = documents,
            onOpenDocument = actions.onOpenDocument,
            isChaptersExpanded = isChaptersExpanded,
            onExpandChapters = onExpandChapters,
            onUserProfileClick = actions.onUserProfileClick,
            onSeeAllReaders = actions.onSeeAllReaders,
            onFindHardcoverMatch = actions.onFindHardcoverMatch,
            chapterPaneMaxHeight = chapterPaneMaxHeight,
            modifier = Modifier.widthIn(max = RIGHT_COLUMN_MAX_WIDTH),
        )
    }
}

/**
 * Left column of the wide body: the held triage panel (a held book only), the grouped About card (description +
 * Genres + Tags), the admins-only [BookVisibilitySection] and a [DetailsSection].
 */
@Composable
private fun WideLeftColumn(
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    lastMatchRow: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = state.book

    Column(modifier = modifier) {
        // A held book is triage-only (spec §8, §10): the held section stands at the top of the body, and the hero
        // carries no Resume unit or action group.
        if (state.isHeld) {
            HeldForReviewSection(
                isReleasing = state.isReleasingFromInbox,
                onReleaseClick = actions.onReleaseFromInboxClick,
                onEditClick = actions.onEditClick,
                onMatchClick = actions.onFindMetadataClick,
                onEditChaptersClick = actions.onEditChaptersClick,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        // The last match, with See what changed and Undo (A-04).
        lastMatchRow(Modifier.padding(bottom = 16.dp))

        // About — description + Genres + Tags, framed in a surfaceContainerLow card.
        AboutSection(
            description = state.descriptionText,
            genres = state.genres,
            tags = state.tags,
            moods = state.moods,
            isLoadingTags = state.isLoadingTags,
            isCard = true,
            isDescriptionExpanded = isDescriptionExpanded,
            onToggleDescriptionExpanded = onToggleDescription,
            onGenreClick = { genre -> actions.onGenreClick(genre.id) },
            onTagClick = { tag -> actions.onTagClick(tag.id, tag.displayName()) },
            onMoodClick = { mood -> actions.onMoodClick(mood.id, mood.displayName()) },
            modifier = Modifier.fillMaxWidth(),
            creditsSlot = null,
        )

        // Who can't see it — straight after About (canvas). Admins only: a member's visibility is
        // null. Never in the triage layout: the held section above says it.
        if (!state.isHeld) {
            state.visibility?.let { visibility ->
                BookVisibilitySection(
                    visibility = visibility,
                    isRestoring = state.isRestoringToAllBooks,
                    onCollectionClick = actions.onCollectionClick,
                    onShowToAllMembers = actions.onRestoreToAllBooksClick,
                    onAddToCollection = actions.onAddToCollectionClick,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        // Details — publisher / published / language / format, then contributor credits.
        DetailsSection(
            publisher = book.publisher,
            publishYear = book.publishYear,
            language = book.language,
            audioFiles = book.audioFiles,
            credits = book.allContributors,
            onContributorClick = actions.onContributorClick,
            modifier = Modifier.padding(top = 16.dp),
            addedAt = state.addedAt,
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
    isHeld: Boolean,
    chapters: List<ChapterUiModel>,
    documents: List<BookDocument>,
    onOpenDocument: (docId: String) -> Unit,
    isChaptersExpanded: Boolean,
    onExpandChapters: () -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit,
    onFindHardcoverMatch: ((bookId: String) -> Unit)?,
    chapterPaneMaxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        // Rating, readers and Hardcover are hidden for a held book (spec §9: hidden, not refused).
        if (!isHeld) {
            // Rating card — your listeners' stars and your own, right above the Readers card.
            BookRatingBlock(
                bookId = bookId,
                isCard = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Readers card — social reading activity. Self-cards only when populated, so no
            // hollow surface is drawn on the common no-readers / Loading / Error paths.
            BookReadersSection(
                bookId = bookId,
                onUserClick = onUserProfileClick,
                isCard = true,
                onSeeAllClick = onSeeAllReaders,
                modifier = Modifier.fillMaxWidth(),
            )

            // Hardcover card — only for a connected user whose book is matched or needs a match.
            BookHardcoverSection(
                bookId = bookId,
                onFindMatch = onFindHardcoverMatch,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Chapters card — header + (optionally collapsed) chapter rows + "show all" affordance.
        WideSectionCard {
            WideChaptersContent(
                chapters = chapters,
                isExpanded = isChaptersExpanded,
                onExpand = onExpandChapters,
                listMaxHeight = chapterPaneMaxHeight,
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

/**
 * Chapters header, the (optionally collapsed) chapter rows, and the "show all" affordance.
 *
 * The rows are a lazy list, so a 300-chapter book composes the rows on screen rather than all of
 * them. It is capped at [listMaxHeight] — the page's viewport — because it scrolls inside the page:
 * the list scrolls first, and at either end the page carries on.
 */
@Composable
internal fun WideChaptersContent(
    chapters: List<ChapterUiModel>,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    listMaxHeight: Dp,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ChaptersHeader(
            chapterCount = chapters.size,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        val displayedChapters = if (isExpanded) chapters else chapters.take(CHAPTER_PREVIEW_LIMIT)
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight)) {
            itemsIndexed(displayedChapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                ChapterListItem(
                    chapter = chapter,
                    chapterNumber = index + 1,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    // TODO(book-detail): mark current chapter once progress→chapter mapping is available.
                    isCurrent = false,
                    showDivider = index < displayedChapters.lastIndex,
                )
            }
        }

        if (chapters.size > CHAPTER_PREVIEW_LIMIT && !isExpanded) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(
                    border = listenUpOutlinedBorder(),
                    onClick = onExpand,
                    shape = ContentShapes.card,
                ) {
                    Text(stringResource(Res.string.book_show_all_chapters, chapters.size))
                }
            }
        }
    }
}
