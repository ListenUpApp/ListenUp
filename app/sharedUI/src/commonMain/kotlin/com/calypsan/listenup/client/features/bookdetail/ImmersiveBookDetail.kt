package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailHeroBand
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailSectionContent
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailTopBar
import com.calypsan.listenup.client.features.bookdetail.components.BookVisibilitySection
import com.calypsan.listenup.client.features.bookdetail.components.DescriptionExpansion
import com.calypsan.listenup.client.features.bookdetail.components.HeldForReviewSection
import com.calypsan.listenup.client.features.bookdetail.components.HeroSocialLine
import com.calypsan.listenup.client.features.bookdetail.components.ListenActionGroup
import com.calypsan.listenup.client.features.bookdetail.components.OfflineReason
import com.calypsan.listenup.client.features.bookdetail.components.PageReaders
import com.calypsan.listenup.client.features.bookdetail.components.ReadersLanding
import com.calypsan.listenup.client.features.bookdetail.components.ResumeUnit
import com.calypsan.listenup.client.features.bookdetail.components.ScanWarningBanner
import com.calypsan.listenup.client.features.bookdetail.components.rememberPageReaders
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailMoreMenu
import kotlinx.coroutines.launch

/**
 * One item of the phone column, keyed. The column is built by walking [phoneDetailItems], so where Readers sits is read
 * off the same list the column draws, never counted by hand.
 */
internal sealed interface PhoneDetailItem {
    /** The item's key in the column. */
    val key: String

    /** The scanner's flag, for an admin, at the top of the hero. */
    data object ScanWarning : PhoneDetailItem {
        override val key = "scan-warning"
    }

    /** Cover, series path, title, byline and stats. */
    data object Hero : PhoneDetailItem {
        override val key = "hero"
    }

    /** A held book's triage panel, where the action unit would be. */
    data object Held : PhoneDetailItem {
        override val key = "held"
    }

    /** The Resume unit over the action group. */
    data object Listen : PhoneDetailItem {
        override val key = "listen"
    }

    /** Who else is reading: one link to Readers. */
    data object Social : PhoneDetailItem {
        override val key = "social"
    }

    /** Who can't see this book (admins only), directly under the actions. */
    data object Visibility : PhoneDetailItem {
        override val key = "visibility"
    }

    /** The last match, with See what changed and Undo. */
    data object LastMatch : PhoneDetailItem {
        override val key = "last-match"
    }

    /** One of the stage-ordered sections. Readers is keyed `readers`: the anchor a landing on it scrolls to. */
    data class Section(
        val section: BookDetailSection,
    ) : PhoneDetailItem {
        override val key: String = section.itemKey()
    }
}

private fun BookDetailSection.itemKey(): String =
    when (this) {
        BookDetailSection.About -> "about"
        BookDetailSection.Ratings -> "ratings"
        BookDetailSection.Hardcover -> "hardcover"
        BookDetailSection.Readers -> READERS_ITEM_KEY
        BookDetailSection.Chapters -> "chapters"
        BookDetailSection.Details -> "details"
    }

/** The Readers section's key in the phone column. */
internal const val READERS_ITEM_KEY = "readers"

/**
 * The phone column's items, in the order it draws them: the admin's scan warning, the hero, the action unit (or the held
 * panel), the social line, the Visibility card, the last match, then the sections in the visit's stage order.
 */
internal fun phoneDetailItems(
    state: BookDetailUiState.Ready,
    hasSocialLine: Boolean,
): List<PhoneDetailItem> =
    buildList {
        if (state.showScanWarning) add(PhoneDetailItem.ScanWarning)
        add(PhoneDetailItem.Hero)
        when {
            state.isHeld -> add(PhoneDetailItem.Held)
            state.isPlaybackAvailable -> add(PhoneDetailItem.Listen)
        }
        if (!state.isHeld && hasSocialLine) add(PhoneDetailItem.Social)
        if (!state.isHeld && state.visibility != null) add(PhoneDetailItem.Visibility)
        add(PhoneDetailItem.LastMatch)
        state.shownSections.forEach { add(PhoneDetailItem.Section(it)) }
    }

/** Where Readers sits in [items], or null when the column shows none. */
internal fun readersItemIndex(items: List<PhoneDetailItem>): Int? =
    items.indexOfFirst { it is PhoneDetailItem.Section && it.section == BookDetailSection.Readers }.takeIf { it >= 0 }

/**
 * Compact (phone) Book Detail column.
 *
 * A plain, untitled [BookDetailTopBar] (Back to Library, More) is hoisted above the scroll; the [LazyColumn] then
 * carries [phoneDetailItems]: the admin's scan warning, the [BookDetailHeroBand], the [ResumeUnit] over the [ListenActionGroup]
 * (or the held triage panel), the [HeroSocialLine], the Visibility card, the last match, then the sections in the
 * visit's stage order. The social line scrolls the column to Readers.
 */
@Composable
internal fun ImmersiveBookDetail(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument> = emptyList(),
    isPreparing: Boolean = false,
    lastMatchRow: @Composable (Modifier) -> Unit = {},
    landOnReaders: Boolean = false,
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    // Full-cast overlay target — set by a folded hero author/narrator line, cleared on dismiss.
    var castRole by remember { mutableStateOf<CastRole?>(null) }

    val book = state.book
    val screenPadding = Modifier.padding(horizontal = Spacing.screenMargin)
    // A held book is triage-only: no social line and no Readers, so its readers are never asked for.
    val readers = if (state.isHeld) PageReaders(hasArrived = false, summary = null) else rememberPageReaders(bookId)
    val summary = readers.summary
    val items = phoneDetailItems(state, hasSocialLine = summary != null)
    val readersIndex = readersItemIndex(items)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Home's finishers line lands here, once per entry: a return to this screen (or a rotation) keeps
    // wherever the reader scrolled to. The index is read off the same list the column draws.
    var landedOnReaders by rememberSaveable(bookId) { mutableStateOf(false) }
    // Focus goes to the Readers heading on a landing, and on every social-line tap: TalkBack follows the jump.
    var focusReadersHeading by rememberSaveable(bookId) { mutableStateOf(landOnReaders) }
    // Once the readers have arrived: until then the section has no height, and the social line above it is not yet
    // in the list, so the index would move after the scroll.
    LaunchedEffect(landOnReaders, readersIndex, readers.hasArrived) {
        if (landOnReaders && !landedOnReaders && readersIndex != null && readers.hasArrived) {
            landedOnReaders = true
            listState.animateScrollToItem(readersIndex)
        }
    }
    val description = DescriptionExpansion(isDescriptionExpanded) { isDescriptionExpanded = !isDescriptionExpanded }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val menu = remember(state) { bookDetailMoreMenu(state) }
        BookDetailTopBar(
            onBackClick = actions.onBackClick,
            menu = menu,
            progressBusy = state.isChangingProgress,
            onMenuItem = actions::perform,
            isHeld = state.isHeld,
        )

        val playerInset = LocalNowPlayingInsets.current.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + playerInset),
        ) {
            items.forEach { entry ->
                item(key = entry.key, contentType = entry.key) {
                    PhoneDetailEntry(
                        entry = entry,
                        bookId = bookId,
                        state = state,
                        actions = actions,
                        documents = documents,
                        isPreparing = isPreparing,
                        summary = summary,
                        description = description,
                        readersLanding =
                            ReadersLanding(
                                focusHeading = focusReadersHeading,
                                onHeadingFocused = { focusReadersHeading = false },
                            ),
                        readersIndex = readersIndex,
                        screenPadding = screenPadding,
                        lastMatchRow = lastMatchRow,
                        onShowCast = { castRole = it },
                        onFocusReadersHeading = { focusReadersHeading = true },
                        onScrollToReaders = { index -> scope.launch { listState.animateScrollToItem(index) } },
                    )
                }
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

@Suppress("LongParameterList")
@Composable
private fun PhoneDetailEntry(
    entry: PhoneDetailItem,
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument>,
    isPreparing: Boolean,
    summary: ReadersSummary?,
    description: DescriptionExpansion,
    readersLanding: ReadersLanding,
    readersIndex: Int?,
    screenPadding: Modifier,
    lastMatchRow: @Composable (Modifier) -> Unit,
    onShowCast: (CastRole) -> Unit,
    onFocusReadersHeading: () -> Unit,
    onScrollToReaders: (Int) -> Unit,
) {
    val book = state.book
    when (entry) {
        // The scanner's flag, for an admin: Review opens Files & audio.
        PhoneDetailItem.ScanWarning -> {
            ScanWarningBanner(
                onReview = actions.onOpenFilesAndAudio,
                modifier = screenPadding.padding(vertical = 8.dp),
            )
        }

        // Identity — cover, series path, title, subtitle, byline and the quick-stat chips.
        PhoneDetailItem.Hero -> {
            BookDetailHeroBand(
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
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // A held book is triage-only: the held section — Release, Edit, Match, Edit
        // chapters — stands where the action unit would, under the title.
        PhoneDetailItem.Held -> {
            HeldForReviewSection(
                isReleasing = state.isReleasingFromInbox,
                onReleaseClick = actions.onReleaseFromInboxClick,
                onEditClick = actions.onEditClick,
                onMatchClick = actions.onFindMetadataClick,
                onEditChaptersClick = actions.onEditChaptersClick,
                modifier = screenPadding.padding(top = 20.dp),
            )
        }

        // The Resume unit directly over the action group: where you are, then what next.
        PhoneDetailItem.Listen -> {
            PhoneListenUnit(
                state = state,
                actions = actions,
                isPreparing = isPreparing,
                screenPadding = screenPadding,
            )
        }

        // Who else is reading: a tap brings the Readers section into view.
        PhoneDetailItem.Social -> {
            if (summary != null) {
                HeroSocialLine(
                    summary = summary,
                    ringColor = MaterialTheme.colorScheme.surface,
                    onOpenReaders = {
                        readersIndex?.let(onScrollToReaders)
                        onFocusReadersHeading()
                    },
                    modifier = screenPadding.padding(top = 12.dp),
                )
            }
        }

        // Who can't see it — admins only; never in the triage layout (the held section says it).
        PhoneDetailItem.Visibility -> {
            state.visibility?.let { visibility ->
                BookVisibilitySection(
                    visibility = visibility,
                    isRestoring = state.isRestoringToAllBooks,
                    onCollectionClick = actions.onCollectionClick,
                    onShowToAllMembers = actions.onRestoreToAllBooksClick,
                    onAddToCollection = actions.onAddToCollectionClick,
                    modifier = screenPadding.padding(top = 16.dp),
                )
            }
        }

        // The last match, with See what changed and Undo — under the actions (A-04).
        PhoneDetailItem.LastMatch -> {
            lastMatchRow(screenPadding.padding(top = 16.dp))
        }

        is PhoneDetailItem.Section -> {
            BookDetailSectionContent(
                section = entry.section,
                bookId = bookId,
                state = state,
                actions = actions,
                documents = documents,
                isWide = false,
                description = description,
                onShowAllCredits = { onShowCast(CastRole.All) },
                modifier = screenPadding.padding(top = 16.dp),
                readersLanding = readersLanding,
            )
        }
    }
}

/** The phone column's Resume unit over its action group. */
@Composable
private fun PhoneListenUnit(
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    isPreparing: Boolean,
    screenPadding: Modifier,
) {
    Column(
        modifier = screenPadding.padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ResumeUnit(
            readingStage = state.readingStage,
            currentChapter = state.currentChapter,
            timeRemainingMs = state.timeRemainingMs,
            progress = state.progress,
            percent = state.progressPercent,
            finishedSpan = state.finishedSpan,
        )
        // Why the primary below is disabled, directly above it.
        if (state.primaryAction == PrimaryAction.UnavailableOffline) OfflineReason(onRetry = actions.onRetryConnection)
        ListenActionGroup(
            primaryAction = state.primaryAction,
            isPreparing = isPreparing,
            downloadStatus = state.downloadStatus,
            isWaitingForWifi = state.isWaitingForWifi,
            downloadSizeBytes = state.downloadSizeBytes,
            supportsDownloads = LocalDeviceContext.current.supportsDownloads,
            canDownload = state.canDownload,
            labelled = false,
            onPrimaryClick = actions.onPlayClick,
            onDownload = actions.onDownloadClick,
            onPauseDownload = actions.onPauseClick,
            onCancelDownload = actions.onCancelClick,
            onRemoveDownload = actions.onDeleteClick,
            onAddToShelf = actions.onAddToShelfClick,
        )
    }
}
