package com.calypsan.listenup.client.features.documentviewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_document_viewer_error
import listenup.composeapp.generated.resources.book_detail_document_viewer_loading
import listenup.composeapp.generated.resources.book_detail_document_viewer_page_of
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen PDF viewer for a locally cached supplement document.
 *
 * Renders the file at [path] page-by-page using [PdfRendererWrapper] (backed by
 * [android.graphics.pdf.PdfRenderer]).  Each page is rendered lazily on [Dispatchers.Default]
 * behind a [Mutex] (PdfRenderer is single-threaded), then displayed in a [LazyColumn].
 *
 * A pinch-to-zoom gesture layer wraps the page list; a page indicator overlay shows the
 * currently-visible page number.
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

    // Shared mutex: PdfRenderer can only render one page at a time.
    val renderMutex = remember(path) { Mutex() }

    val listState = rememberLazyListState()
    val firstVisible = listState.firstVisibleItemIndex

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = basenameOf(path),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
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
                ZoomableBox(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(pageCount) { pageIndex ->
                            PdfPageItem(
                                pageIndex = pageIndex,
                                wrapper = wrapper,
                                renderMutex = renderMutex,
                            )
                        }
                    }
                }

                if (wrapper.pageCount > 0) {
                    PageIndicator(
                        currentPage = firstVisible + 1,
                        totalPages = wrapper.pageCount,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp),
                    )
                }
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
// Page indicator
// ---------------------------------------------------------------------------

/**
 * Small pill overlay showing "[currentPage] / [totalPages]".
 */
@Composable
private fun PageIndicator(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(Res.string.book_detail_document_viewer_page_of, currentPage, totalPages),
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
