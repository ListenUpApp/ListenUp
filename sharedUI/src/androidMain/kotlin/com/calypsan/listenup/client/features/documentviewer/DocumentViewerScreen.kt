package com.calypsan.listenup.client.features.documentviewer

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import kotlinx.coroutines.Dispatchers
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
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Full-screen PDF viewer for a locally cached supplement document.
 *
 * Renders the file at [path] page-by-page using [PdfRendererWrapper] (backed by
 * [android.graphics.pdf.PdfRenderer]).  Each page is rendered lazily on [Dispatchers.Default]
 * behind a [Mutex] (PdfRenderer is single-threaded), then displayed in a [LazyColumn].
 *
 * A pinch-to-zoom gesture layer wraps the page list. Chrome (top bar + bottom dock) is
 * toggled by tapping the page area; a tap-detection [pointerInput] intercepts single taps
 * without consuming scroll/zoom gestures by using a separate [pointerInput] key on an
 * outer [Box], leaving [ZoomableBox]'s own [pointerInput] to handle transform gestures.
 *
 * The bottom dock shows a [ReaderNowPlayingStrip] (when playback is active) above a
 * [ReaderPageScrubber]. Playback state is sourced from the process-singleton
 * [NowPlayingViewModel], matching the shell's mini-player wiring.
 *
 * @param path Absolute path to the local PDF file.
 * @param onBack Called when the user taps the navigation-icon back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentViewerScreen(
    path: String,
    onBack: () -> Unit,
) {
    // Open the renderer once per distinct path; close it when path changes or composable leaves.
    val wrapper = remember(path) { runCatching { PdfRendererWrapper(path) }.getOrNull() }
    DisposableEffect(path) {
        onDispose { wrapper?.close() }
    }

    val renderMutex = remember(path) { Mutex() }
    val listState = rememberLazyListState()
    val firstVisible = listState.firstVisibleItemIndex
    val scope = rememberCoroutineScope()

    // Playback state — reuse the process-singleton NowPlayingViewModel, same as the shell.
    val nowPlayingViewModel: NowPlayingViewModel = koinViewModel()
    val nowPlayingScreenState by nowPlayingViewModel.screenState.collectAsStateWithLifecycle()
    val nowPlayingProgress by nowPlayingViewModel.progress.collectAsStateWithLifecycle()

    var chromeVisible by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ReaderTopBar(
                title = basenameOf(path),
                onBack = onBack,
                chromeVisible = chromeVisible,
                onGrid = { showGrid = true },
            )
        },
        bottomBar = {
            ReaderBottomDock(
                chromeVisible = chromeVisible,
                nowPlayingState = nowPlayingScreenState.state,
                nowPlayingProgress = nowPlayingProgress,
                onPlayPause = nowPlayingViewModel::playPause,
                wrapper = wrapper,
                firstVisible = firstVisible,
                onSeekToIndex = { index -> scope.launch { listState.scrollToItem(index) } },
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            if (wrapper == null) {
                ErrorContent()
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
    var showOverflowMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = chromeVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        TopAppBar(
            title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = onGrid) {
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = stringResource(Res.string.book_detail_document_reader_toggle_grid),
                    )
                }
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
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
 * Animating bottom dock containing [ReaderNowPlayingStrip] + [ReaderPageScrubber].
 */
@Composable
private fun ReaderBottomDock(
    chromeVisible: Boolean,
    nowPlayingState: NowPlayingState,
    nowPlayingProgress: PlaybackProgress,
    onPlayPause: () -> Unit,
    wrapper: PdfRendererWrapper?,
    firstVisible: Int,
    onSeekToIndex: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = chromeVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ReaderNowPlayingStrip(
                state = nowPlayingState,
                progress = nowPlayingProgress,
                onPlayPause = onPlayPause,
            )
            if (wrapper != null && wrapper.pageCount > 0) {
                ReaderPageScrubber(
                    currentPage = firstVisible + 1,
                    pageCount = wrapper.pageCount,
                    onSeekToIndex = onSeekToIndex,
                )
            }
        }
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
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.roundToPx() }

        val bitmapState by produceState<Bitmap?>(initialValue = null, pageIndex, targetWidthPx) {
            value =
                withContext(Dispatchers.Default) {
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
