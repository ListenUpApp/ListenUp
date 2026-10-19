package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.features.bookdetail.BookDetailActions
import com.calypsan.listenup.client.features.bookdetail.BookDetailScanWarning
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailColumns
import kotlinx.coroutines.launch

/** The side column's width (spec §3.2.4 "Side (360 px)"; the Android tablet board's side column is the same). */
private val RIGHT_COLUMN_MAX_WIDTH = 360.dp

/**
 * Wide (tablet / desktop) Book Detail layout on the Material 3 Expressive "Color Block" design.
 *
 * A plain, untitled [BookDetailTopBar] is hoisted above the scroll; the scrolling [Column] then carries, in
 * order: an optional [OfflineBanner] and [BookDetailScanWarning] advisory, the [WideHeroBand] (identity, then the
 * [ResumeUnit], a labelled [ListenActionGroup] and the [HeroSocialLine] inside it, spec §3.2.4), and the two columns
 * from [bookDetailColumns]: the reading column (Chapters, About, Series) and the side column (Readers, Ratings,
 * Hardcover, Details), each in the visit's stage order. A section never changes column.
 *
 * The main column flexes with `weight(1f)`; the side column caps at [RIGHT_COLUMN_MAX_WIDTH].
 */
@Composable
fun WideBookDetail(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument> = emptyList(),
    isPreparing: Boolean = false,
    /** Book Detail's last-match row, at the top of the main column. */
    lastMatchRow: @Composable (Modifier) -> Unit = {},
    /** Scroll the Readers card into view once, on arrival (spec §6.4). */
    landOnReaders: Boolean = false,
) {
    // The Readers card's anchor: the social line, and Home's finishers line on arrival (spec §6.4), bring it into view.
    val readersRequester = remember { BringIntoViewRequester() }
    // Once per entry: a return to this screen (or a rotation) keeps wherever the reader scrolled to.
    var landedOnReaders by rememberSaveable(bookId) { mutableStateOf(false) }
    var focusedReaders by rememberSaveable(bookId) { mutableStateOf(false) }
    // A held book is triage-only: no social line and no Readers, so its readers are never asked for.
    val readers = if (state.isHeld) PageReaders(hasArrived = false, summary = null) else rememberPageReaders(bookId)
    // Once the readers have arrived: until then the hero has no social line and the card no rows, so both grow after
    // a landing that went first, and push Readers back out of view.
    LaunchedEffect(landOnReaders, readers.hasArrived) {
        if (landOnReaders && !landedOnReaders && readers.hasArrived) {
            landedOnReaders = true
            // After the layout that draws them, so the card has its height in the scroll it is brought to.
            withFrameNanos { }
            readersRequester.bringIntoView()
        }
    }
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    // Full-cast overlay target — set by a folded hero author/narrator line, cleared on dismiss.
    var castRole by remember { mutableStateOf<CastRole?>(null) }
    val scope = rememberCoroutineScope()

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

            // Identity, then the Resume unit, the action group and the social line inside the hero (spec §3.2.4).
            WideHero(
                bookId = bookId,
                state = state,
                actions = actions,
                isPreparing = isPreparing,
                onShowCast = { castRole = it },
                summary = readers.summary,
                onOpenReaders = { scope.launch { readersRequester.bringIntoView() } },
            )

            WideBodyColumns(
                bookId = bookId,
                state = state,
                actions = actions,
                documents = documents,
                description =
                    DescriptionExpansion(isDescriptionExpanded) { isDescriptionExpanded = !isDescriptionExpanded },
                lastMatchRow = lastMatchRow,
                readersModifier = Modifier.bringIntoViewRequester(readersRequester),
                readersLanding =
                    ReadersLanding(
                        focusHeading = landOnReaders && !focusedReaders,
                        onHeadingFocused = { focusedReaders = true },
                    ),
                modifier = screenPadding.fillMaxWidth().padding(top = 24.dp),
            )
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
 * The wide hero (spec §3.2.4): identity, then the Resume unit, the labelled action group and the social line inside
 * it, unless the book is held (the triage panel replaces the action unit) or playback is unavailable here.
 */
@Composable
private fun WideHero(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    isPreparing: Boolean,
    onShowCast: (CastRole) -> Unit,
    summary: ReadersSummary?,
    onOpenReaders: () -> Unit,
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
        social = {
            // Hidden for a held book, as its Readers section is (spec §2.10).
            if (!state.isHeld && summary != null) {
                HeroSocialLine(
                    summary = summary,
                    ringColor = MaterialTheme.colorScheme.surface,
                    onOpenReaders = onOpenReaders,
                )
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * The two wide columns (spec §3.2.4). Main: the held triage panel when held, the last match, then Chapters, About and
 * Series in stage order. Side: the admins' Visibility card at its top (rule 4), then Readers, Ratings, Hardcover and
 * Details in stage order.
 */
@Suppress("LongParameterList")
@Composable
private fun WideBodyColumns(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument>,
    description: DescriptionExpansion,
    lastMatchRow: @Composable (Modifier) -> Unit,
    readersModifier: Modifier,
    readersLanding: ReadersLanding,
    modifier: Modifier = Modifier,
) {
    val columns = bookDetailColumns(state.shownSections())
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            // A held book is triage-only (spec §8, §10): the held section stands at the top of the body, and the hero
            // carries no Resume unit or action group.
            if (state.isHeld) {
                HeldForReviewSection(
                    isReleasing = state.isReleasingFromInbox,
                    onReleaseClick = actions.onReleaseFromInboxClick,
                    onEditClick = actions.onEditClick,
                    onMatchClick = actions.onFindMetadataClick,
                    onEditChaptersClick = actions.onEditChaptersClick,
                )
            }
            // The last match, with See what changed and Undo (A-04).
            lastMatchRow(Modifier)
            columns.main.forEach { section ->
                BookDetailSectionContent(
                    section = section,
                    bookId = bookId,
                    state = state,
                    actions = actions,
                    documents = documents,
                    isWide = true,
                    description = description,
                )
            }
        }

        Column(
            modifier = Modifier.widthIn(max = RIGHT_COLUMN_MAX_WIDTH),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Who can't see it — admins only, at the top of the side column. Never in the triage layout.
            if (!state.isHeld) {
                state.visibility?.let { visibility ->
                    BookVisibilitySection(
                        visibility = visibility,
                        isRestoring = state.isRestoringToAllBooks,
                        onCollectionClick = actions.onCollectionClick,
                        onShowToAllMembers = actions.onRestoreToAllBooksClick,
                        onAddToCollection = actions.onAddToCollectionClick,
                    )
                }
            }
            columns.side.forEach { section ->
                BookDetailSectionContent(
                    section = section,
                    bookId = bookId,
                    state = state,
                    actions = actions,
                    documents = documents,
                    isWide = true,
                    description = description,
                    modifier = if (section == BookDetailSection.Readers) readersModifier else Modifier,
                    readersLanding = readersLanding,
                )
            }
        }
    }
}
