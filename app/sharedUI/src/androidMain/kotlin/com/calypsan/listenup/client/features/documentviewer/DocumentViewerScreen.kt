package com.calypsan.listenup.client.features.documentviewer

import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import com.calypsan.listenup.client.foldable.LocalFold
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.features.nowplaying.OverlayDispatch
import com.calypsan.listenup.client.features.nowplaying.PlayerEventsEffect
import com.calypsan.listenup.client.features.nowplaying.PlayerNavigation
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.core.IODispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_document_reader_controls_hint
import listenup.composeapp.generated.resources.book_detail_document_reader_toggle_grid
import listenup.composeapp.generated.resources.book_detail_document_viewer_error
import listenup.composeapp.generated.resources.book_detail_document_viewer_loading
import listenup.composeapp.generated.resources.book_detail_document_viewer_page_of
import listenup.composeapp.generated.resources.book_detail_more_options
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Full-screen PDF viewer for a locally cached supplement document.
 *
 * Renders the file at [path] page-by-page using [PdfRendererWrapper] (backed by
 * [android.graphics.pdf.PdfRenderer]), opened on the IO dispatcher behind a loading state.  Each page is rendered lazily on [Dispatchers.Default]
 * behind a [Mutex] (PdfRenderer is single-threaded), then displayed in a [LazyColumn].
 *
 * A pinch-to-zoom gesture layer wraps the page list. Chrome (top bar + bottom dock) is
 * toggled by tapping the page area; a tap-detection [pointerInput] intercepts single taps
 * without consuming scroll/zoom gestures by using a separate [pointerInput] key on an
 * outer [Box], leaving [ZoomableBox]'s own [pointerInput] to handle transform gestures.
 *
 * The bottom dock shows a [ReaderNowPlayingStrip] (when playback is active) above a
 * [ReaderPageScrubber]; with room — a tablet, a foldable half open like a book — the [ReaderListeningPane] sits beside
 * the page in the strip's place ([ReaderFrame]). Playback state is sourced from this entry's own [PlayerViewModel] — a projection
 * of the one process-wide PlayerSession, so it agrees with the shell's mini player.
 *
 * @param path Absolute path to the local PDF file.
 * @param onBack Called when the user taps the navigation-icon back button.
 * @param playerNavigation Where this entry's player events lead (Sign in from the strip, a problem card's way out) —
 *   the same places the mini-player's lead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentViewerScreen(
    path: String,
    onBack: () -> Unit,
    playerNavigation: PlayerNavigation,
) {
    val renderMutex = remember(path) { Mutex() }
    val document by rememberPdfDocument(path, renderMutex)
    val wrapper = (document as? PdfDocument.Ready)?.wrapper

    val listState = rememberLazyListState()
    val firstVisible = listState.firstVisibleItemIndex
    val scope = rememberCoroutineScope()

    // Playback state — this nav entry's own PlayerViewModel, a projection of the shared PlayerSession, so it
    // shows exactly what the shell's mini player shows.
    val nowPlayingViewModel: PlayerViewModel = koinViewModel()
    val playerState by nowPlayingViewModel.uiState.collectAsStateWithLifecycle()
    // Held as a State and read only by the strip's time-left line, so the position tick does not
    // recompose the page list — the same deferral NowPlayingHost uses.
    val nowPlayingProgress = nowPlayingViewModel.progress.collectAsStateWithLifecycle()

    val nowPlayingOverlay by nowPlayingViewModel.overlay.collectAsStateWithLifecycle()
    val playerActions = remember(nowPlayingViewModel) { nowPlayingViewModel.readerPlayerActions() }
    // This entry's player is its own instance, so its events are collected here, not by the shell's host.
    PlayerEventsEffect(viewModel = nowPlayingViewModel, navigation = playerNavigation)

    var chromeVisible by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(false) }

    ReaderFrame(
        state = playerState,
        progress = { nowPlayingProgress.value },
        actions = playerActions,
        chromeVisible = chromeVisible,
        topBar = {
            ReaderTopBar(
                title = basenameOf(path),
                onBack = onBack,
                chromeVisible = chromeVisible,
                onGrid = { showGrid = true },
            )
        },
        scrubber = {
            if (wrapper != null && wrapper.pageCount > 0) {
                ReaderPageScrubber(
                    currentPage = firstVisible + 1,
                    pageCount = wrapper.pageCount,
                    onSeekToIndex = { index -> scope.launch { listState.scrollToItem(index) } },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (wrapper == null) {
                if (document == PdfDocument.Opening) OpeningContent() else ErrorContent()
            } else {
                val pageCount = wrapper.pageCount
                // Outer Box: tap-to-toggle chrome without consuming zoom/pan gestures.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                            },
                ) {
                    ZoomableBox(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(pageCount) { pageIndex ->
                                PdfPageItem(pageIndex = pageIndex, wrapper = wrapper, renderMutex = renderMutex)
                            }
                        }
                    }
                }
                // When chrome is hidden, show a small pill so the user can find their way back.
                AnimatedVisibility(
                    visible = !chromeVisible && pageCount > 0,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    HintPill(
                        text =
                            stringResource(
                                Res.string.book_detail_document_reader_controls_hint,
                                firstVisible + 1,
                                pageCount,
                            ),
                    )
                }
                // Grid overlay — full-bleed, top layer; only shown when showGrid is true.
                AnimatedVisibility(
                    visible = showGrid,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PageGridOverlay(
                        wrapper = wrapper,
                        renderMutex = renderMutex,
                        pageCount = pageCount,
                        currentPage = firstVisible,
                        onSelect = { idx ->
                            scope.launch { listState.scrollToItem(idx) }
                            showGrid = false
                        },
                        onClose = { showGrid = false },
                    )
                }
            }
        }
    }

    // The pane's Speed and Sleep open this entry's own sheets, as Now Playing's do.
    OverlayDispatch(
        overlay = nowPlayingOverlay,
        progress = { nowPlayingProgress.value },
        loaded = playerState as? PlayerUiState.Loaded,
        viewModel = nowPlayingViewModel,
        onNavigateToContributor = {},
    )
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

/**
 * The reader's frame: the top bar over the [pages], and the player either as the strip in the bottom dock above the
 * [scrubber] or, when the screen has room ([readerLayout]), as the [ReaderListeningPane] beside the page with the
 * [scrubber] under the page. The window and the fold are read here, as Now Playing reads them.
 *
 * @param progress reads the playback position; only the strip's and the pane's leaves call it.
 */
@Composable
internal fun ReaderFrame(
    state: PlayerUiState,
    progress: () -> PlayerProgress,
    actions: ReaderPlayerActions,
    chromeVisible: Boolean,
    topBar: @Composable () -> Unit,
    scrubber: @Composable () -> Unit,
    pages: @Composable () -> Unit,
) {
    val loaded = state as? PlayerUiState.Loaded
    val layout = readerLayout(currentWindowAdaptiveInfoV2().windowSizeClass, LocalFold.current, loaded != null)
    if (layout is ReaderLayout.Beside && loaded != null) {
        Scaffold(topBar = topBar) { paddingValues ->
            ReaderBeside(
                split = layout.splitAt,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                page = {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { pages() }
                        ReaderBottomDock(chromeVisible = chromeVisible) { scrubber() }
                    }
                },
                pane = { ReaderListeningPane(state = loaded, progress = progress, actions = actions) },
            )
        }
    } else {
        Scaffold(
            topBar = topBar,
            bottomBar = {
                ReaderBottomDock(chromeVisible = chromeVisible) {
                    ReaderNowPlayingStrip(
                        state = state,
                        progress = progress,
                        onPrimary = actions.onPrimary,
                        onSkipBack = actions.onSkipBack,
                        onKeepListening = actions.onKeepListening,
                    )
                    scrubber()
                }
            },
        ) { paddingValues ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            ) {
                pages()
            }
        }
    }
}

/** The listening pane's width beside the page in a wide window. */
private val READER_PANE_WIDTH = 360.dp

/** Clearance either side of the fold, so nothing sits on the crease even when the hinge reports no thickness. */
private val FOLD_CLEARANCE = 16.dp

/**
 * The [page] and the [pane] side by side, divided at [split]. A hinge's bounds are window pixels, so the frame reads
 * its own left edge in the window to place the split; that comes from the fold, never from measuring the content.
 */
@Composable
private fun ReaderBeside(
    split: ReaderSplit,
    modifier: Modifier,
    page: @Composable () -> Unit,
    pane: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var frameLeft by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.onPlaced { frameLeft = it.positionInWindow().x.roundToInt() }) {
        val columns =
            with(density) {
                readerColumns(
                    split = split,
                    left = frameLeft,
                    width = constraints.maxWidth,
                    paneWidth = READER_PANE_WIDTH.roundToPx(),
                    clearance = FOLD_CLEARANCE.roundToPx(),
                    layoutDirection = layoutDirection,
                )
            }
        Row(Modifier.fillMaxSize()) {
            with(density) {
                Box(Modifier.width(columns.page.toDp()).fillMaxHeight()) { page() }
                Spacer(Modifier.width(columns.gap.toDp()))
                Box(Modifier.width(columns.pane.toDp()).fillMaxHeight()) { pane() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Opening the document
// ---------------------------------------------------------------------------

/** Where the document is on its way to the screen. */
private sealed interface PdfDocument {
    /** The file is being opened off the main thread. */
    data object Opening : PdfDocument

    /** The file could not be opened as a PDF. */
    data object Failed : PdfDocument

    /** Open, and owned by the composition until it leaves. */
    data class Ready(
        val wrapper: PdfRendererWrapper,
    ) : PdfDocument
}

/**
 * Opens [path] on the IO dispatcher and closes it when the screen leaves or the path changes.
 *
 * Opening a PDF reads the file's cross-reference table, which on a large scan is long enough to
 * drop frames if it runs in composition. The close waits for [renderMutex], so a page still being
 * rendered on another thread finishes before the renderer under it is torn down.
 */
@Composable
private fun rememberPdfDocument(
    path: String,
    renderMutex: Mutex,
): State<PdfDocument> =
    produceState<PdfDocument>(initialValue = PdfDocument.Opening, path, renderMutex) {
        var opened: PdfRendererWrapper? = null
        try {
            // Assigned inside the block, so a wrapper opened just as the screen left is still seen
            // by the finally below and closed rather than leaked.
            withContext(IODispatcher) { opened = runCatching { PdfRendererWrapper(path) }.getOrNull() }
            value = opened?.let { PdfDocument.Ready(it) } ?: PdfDocument.Failed
            awaitCancellation()
        } finally {
            opened?.let { wrapper ->
                withContext(NonCancellable + IODispatcher) { renderMutex.withLock { wrapper.close() } }
            }
        }
    }

@Composable
private fun OpeningContent() {
    val loadingDescription = stringResource(Res.string.book_detail_document_viewer_loading)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = loadingDescription },
        contentAlignment = Alignment.Center,
    ) {
        ListenUpLoadingIndicator()
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/**
 * Animating top bar with back navigation + grid toggle + overflow actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    chromeVisible: Boolean,
    onGrid: () -> Unit,
) {
    val haptics = LocalHaptics.current
    var showOverflowMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = chromeVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        ListenUpTopAppBar(
            title = title,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = {
                        haptics.press()
                        onGrid()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GridView,
                        contentDescription = stringResource(Res.string.book_detail_document_reader_toggle_grid),
                    )
                }
                Box {
                    IconButton(
                        onClick = {
                            haptics.press()
                            showOverflowMenu = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(Res.string.book_detail_more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        // Populated in future tasks (3b, 3c).
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Bottom dock
// ---------------------------------------------------------------------------

/**
 * Animating bottom dock: the [ReaderNowPlayingStrip] above the [ReaderPageScrubber], or the scrubber alone under the
 * page when the listening pane takes the strip's place.
 */
@Composable
private fun ReaderBottomDock(
    chromeVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = chromeVisible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

// ---------------------------------------------------------------------------
// Page item
// ---------------------------------------------------------------------------

/**
 * Renders a single PDF page lazily.
 *
 * Uses [produceState] to kick off rendering on [Dispatchers.Default] behind [renderMutex]
 * (PdfRenderer is single-threaded).  While rendering, shows a [CircularProgressIndicator].
 */
@Composable
private fun PdfPageItem(
    pageIndex: Int,
    wrapper: PdfRendererWrapper,
    renderMutex: Mutex,
    renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.roundToPx() }

        val bitmapState by produceState<Bitmap?>(initialValue = null, pageIndex, targetWidthPx) {
            value =
                withContext(renderDispatcher) {
                    renderMutex.withLock {
                        runCatching { wrapper.renderPage(pageIndex, targetWidthPx) }.getOrNull()
                    }
                }
        }

        val bitmap = bitmapState
        if (bitmap == null) {
            val loadingDescription = stringResource(Res.string.book_detail_document_viewer_loading)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Reserve vertical space proportional to an A4 aspect ratio while loading.
                        .size(maxWidth, maxWidth * 1.414f)
                        .semantics { contentDescription = loadingDescription },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription =
                    stringResource(
                        Res.string.book_detail_document_viewer_page_of,
                        pageIndex + 1,
                        wrapper.pageCount,
                    ),
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Zoom container
// ---------------------------------------------------------------------------

/**
 * A [Box] that applies pinch-to-zoom and pan via [detectTransformGestures].
 *
 * Zoom is clamped to [1f, 4f]; pan is unclamped (the natural boundary is the
 * content inside the box).  State is [rememberSaveable] so it survives recomposition
 * but resets on back-stack pop.
 *
 * The tap-to-toggle chrome gesture lives in an *outer* [Box] (see [DocumentViewerScreen]),
 * keeping this composable's [pointerInput] focused solely on transform gestures.
 */
@Composable
private fun ZoomableBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }

    Box(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 4f)
                        // Reset pan when scale goes back to 1 to avoid a stuck offset.
                        if (newScale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                        scale = newScale
                    }
                }.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Hidden-chrome hint pill
// ---------------------------------------------------------------------------

/**
 * Small pill shown when chrome is hidden, displaying the page position hint text.
 */
@Composable
private fun HintPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Error state
// ---------------------------------------------------------------------------

@Composable
private fun ErrorContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.book_detail_document_viewer_error),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
