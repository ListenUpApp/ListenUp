package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.transitions.bookCoverHeroKey
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailErrorState
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailTopBar
import com.calypsan.listenup.client.features.bookdetail.components.PhoneHeroCoverSize
import com.calypsan.listenup.client.features.bookdetail.components.WideBookDetail
import com.calypsan.listenup.client.features.match.MatchReceiptHost
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailNavAction
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchEvent
import com.calypsan.listenup.core.BookId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_document_viewer_coming_soon
import listenup.composeapp.generated.resources.match_undo_expired
import listenup.composeapp.generated.resources.match_undone
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The book detail screen: a hero band (cover, title, byline, quick stats), the listen actions, then the
 * sections in the order the visit's stage calls for.
 *
 * Android takes no cover-derived colour: the screen stays on Material You colour roles by design, so there
 * is no cover wash behind the hero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    onBackClick: () -> Unit,
    onEditClick: (bookId: String) -> Unit,
    onMatchDetailsClick: (bookId: String) -> Unit,
    onEditChaptersClick: (bookId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onGenreClick: (genreId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit = {},
    onFindHardcoverMatch: ((bookId: String) -> Unit)? = null,
    /** Opens an admin collection from the Visibility card; null shows the names as plain labels. */
    onCollectionClick: ((collectionId: String) -> Unit)? = null,
    /** Opens the in-app reader on a downloaded document, titled with this book's name. */
    onOpenDocumentViewer: (localPath: String, bookTitle: String) -> Unit = { _, _ -> },
    /** Open with the Readers section in view, once: Home's finishers line. */
    landOnReaders: Boolean = false,
    /** The receipt after Match details applied; a slot so a test can render the screen without its ViewModel. */
    matchReceipt: @Composable (
        Modifier,
    ) -> Unit = { modifier -> MatchReceiptHost(subjectId = bookId, modifier = modifier) },
    viewModel: BookDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    val snackbarHostState = LocalSnackbarHostState.current
    val viewerComingSoonLabel = stringResource(Res.string.book_detail_document_viewer_coming_soon)
    val navPlatformActions: BookDetailPlatformActions = koinInject()

    // Tells the handoff layer which book is on screen, so Android's Continue On can offer it to
    // another device. A DisposableEffect rather than a LaunchedEffect because the *departure*
    // matters as much as the arrival — and it clears by id, so the outgoing screen (disposed after
    // the incoming one appears) cannot wipe a newer book's claim.
    DisposableEffect(bookId) {
        val id = BookId(bookId)
        navPlatformActions.onBookScreenShown(id)
        onDispose { navPlatformActions.onBookScreenHidden(id) }
    }

    // Consume one-shot navigation events from the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                is BookDetailNavAction.OpenDocumentViewer -> {
                    // The reader is titled with this book's name, as iOS's is.
                    val title = (viewModel.state.value as? BookDetailUiState.Ready)?.run { book.title }.orEmpty()
                    onOpenDocumentViewer(action.localPath, title)
                }

                is BookDetailNavAction.ShowViewerComingSoon -> {
                    snackbarHostState.showSnackbar(viewerComingSoonLabel)
                }

                is BookDetailNavAction.BookDeleted -> {
                    // Purge this device's copy before leaving: the book's files are gone on the
                    // server, so a download left behind would keep playing a book that no longer
                    // exists — offline, indefinitely, with no way to reach it from the library.
                    navPlatformActions.deleteDownload(BookId(bookId))
                    // Then leave, before the tombstone lands and the row disappears underneath us.
                    onBackClick()
                }
            }
        }
    }

    // "Undo last match" says how it ended as the receipt does: undone, or too late.
    LaunchedEffect(viewModel) {
        viewModel.lastMatchEvents.collect { event ->
            val message =
                when (event) {
                    LastMatchEvent.Undone -> getString(Res.string.match_undone)
                    LastMatchEvent.Expired -> getString(Res.string.match_undo_expired)
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (val s = state) {
            is BookDetailUiState.Loading -> {
                BookDetailLoadingFrame(bookId = bookId, onBackClick = onBackClick)
            }

            is BookDetailUiState.Error -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    BookDetailTopBar(
                        onBackClick = onBackClick,
                        menu = emptyList(),
                        progressBusy = false,
                        onMenuItem = {},
                        isHeld = false,
                    )
                    // Retry asks the server again (a reconnect runs catch-up); the page turns Ready by itself when
                    // the book lands in Room.
                    BookDetailErrorState(
                        error = s.error,
                        onRetry = { viewModel.retryConnection() },
                        onBack = onBackClick,
                    )
                }
            }

            is BookDetailUiState.Ready -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    BookDetailReadyContent(
                        bookId = bookId,
                        state = s,
                        viewModel = viewModel,
                        onBackClick = onBackClick,
                        onEditClick = onEditClick,
                        onMatchDetailsClick = onMatchDetailsClick,
                        onEditChaptersClick = onEditChaptersClick,
                        onSeriesClick = onSeriesClick,
                        onContributorClick = onContributorClick,
                        onGenreClick = onGenreClick,
                        onTagClick = onTagClick,
                        onMoodClick = onMoodClick,
                        onUserProfileClick = onUserProfileClick,
                        onSeeAllReaders = onSeeAllReaders,
                        onFindHardcoverMatch = onFindHardcoverMatch,
                        onCollectionClick = onCollectionClick,
                        landOnReaders = landOnReaders,
                    )
                    // The receipt after Match details applied, above the mini player.
                    matchReceipt(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(LocalNowPlayingInsets.current.union(WindowInsets.navigationBars)),
                    )
                }
            }
        }
    }
}

/**
 * The detail screen's first frame, before the book has loaded: the top bar and the cover, placed
 * where the loaded screen keeps them.
 *
 * The cover is here because it is the hero transition's landing target. A bare spinner has none, so
 * the shared element leaving the library grid had nothing to fly into and silently did nothing —
 * the animation exists only if this frame carries the same [bookCoverHeroKey] the grid cell did.
 * [com.calypsan.listenup.client.design.components.BookCoverImage] resolves a cover from [bookId]
 * alone, and the grid has already warmed Coil's memory cache, so it renders on the first frame.
 *
 * Showing the cover you tapped also beats a lone spinner on its own terms: the screen answers "did
 * my tap land?" straight away instead of making you wait to find out.
 */
@Composable
private fun BookDetailLoadingFrame(
    bookId: String,
    onBackClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Nothing is offered until the book is known, as on iOS: no ⋮ at all.
        BookDetailTopBar(
            onBackClick = onBackClick,
            menu = emptyList(),
            progressBusy = false,
            onMenuItem = {},
            isHeld = false,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The cover ALONE, flat, exactly as BookDetailHeroBand draws it: the same box and corner, so the
            // shared element lands where the loaded screen keeps it.
            BookCoverImage(
                bookId = bookId,
                coverPath = null,
                contentDescription = null,
                heroKey = bookCoverHeroKey(bookId),
                heroClipShape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(PhoneHeroCoverSize).clip(MaterialTheme.shapes.extraLarge),
            )
        }
    }
}

/**
 * Main content container that handles responsive layout.
 * Uses WideBookDetail for tablets, ImmersiveBookDetail for phones.
 */
@Composable
fun BookDetailContent(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument> = emptyList(),
    isPreparing: Boolean = false,
    /** Book Detail's last-match row ("Details matched … · See what changed · Undo last match"), under the actions. */
    lastMatchRow: @Composable (Modifier) -> Unit = {},
    /** Scroll the Readers section into view once, on arrival. */
    landOnReaders: Boolean = false,
) {
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass

    // Two-pane only at EXPANDED width (~840dp+): the wide hero band + About/Credits + Readers/Chapters
    // columns need real room. At medium width (portrait tablets, unfolded-foldable portrait) the
    // single fluid [ImmersiveBookDetail] column reads better than a cramped two-pane.
    val useTwoPane =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    if (useTwoPane) {
        WideBookDetail(
            bookId = bookId,
            state = state,
            actions = actions,
            documents = documents,
            isPreparing = isPreparing,
            lastMatchRow = lastMatchRow,
            landOnReaders = landOnReaders,
        )
    } else {
        ImmersiveBookDetail(
            bookId = bookId,
            state = state,
            actions = actions,
            documents = documents,
            isPreparing = isPreparing,
            lastMatchRow = lastMatchRow,
            landOnReaders = landOnReaders,
        )
    }
}
