package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_document_pages_back
import listenup.composeapp.generated.resources.book_detail_document_pages_title
import listenup.composeapp.generated.resources.book_detail_document_viewer_loading
import listenup.composeapp.generated.resources.book_detail_document_viewer_page_of
import listenup.composeapp.generated.resources.book_reader_scrubber_page_count
import org.jetbrains.compose.resources.stringResource

private const val THUMBNAIL_TARGET_WIDTH_PX = 150

// A4 aspect ratio used as placeholder while thumbnail renders.
private const val A4_ASPECT_RATIO = 1f / 1.414f

/**
 * Full-screen "All Pages" grid overlay for the PDF reader.
 *
 * Renders a thumbnail for each page on demand using [produceState], serialised through
 * [renderMutex] (PdfRenderer is single-threaded — exactly the same pattern as [PdfPageItem]).
 * Tapping a cell calls [onSelect] with the zero-based page index; the back arrow calls [onClose].
 *
 * @param wrapper Open [PdfRendererWrapper] shared with the main reader view.
 * @param renderMutex Mutex shared with the main reader — thumbnail renders serialise alongside
 *   full-page renders so the single-threaded [android.graphics.pdf.PdfRenderer] is never
 *   accessed concurrently.
 * @param pageCount Total number of pages in the document.
 * @param currentPage Zero-based index of the currently visible page in the reader.
 * @param onSelect Called with the zero-based page index the user tapped.
 * @param onClose Called when the user taps the navigation back icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageGridOverlay(
    wrapper: PdfRendererWrapper,
    renderMutex: Mutex,
    pageCount: Int,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceDim,
    ) {
        Column {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.book_detail_document_pages_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.book_detail_document_pages_back),
                        )
                    }
                },
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(pageCount) { index ->
                    PageGridCell(
                        index = index,
                        wrapper = wrapper,
                        renderMutex = renderMutex,
                        pageCount = pageCount,
                        isCurrent = index == currentPage,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

/**
 * Single cell in the [PageGridOverlay] grid.
 *
 * Renders a small thumbnail via [produceState] on [Dispatchers.Default] behind [renderMutex],
 * mirroring the render/lifecycle pattern of [PdfPageItem]: the lock serialises thumbnail renders
 * with the main reader's full-page renders so the single-threaded [android.graphics.pdf.PdfRenderer]
 * is never double-entered.  While the bitmap is pending a placeholder [Box] reserves A4-proportioned
 * space.
 */
@Composable
private fun PageGridCell(
    index: Int,
    wrapper: PdfRendererWrapper,
    renderMutex: Mutex,
    pageCount: Int,
    isCurrent: Boolean,
    onSelect: (Int) -> Unit,
) {
    val pageDescription = stringResource(Res.string.book_detail_document_viewer_page_of, index + 1, pageCount)
    val loadingDescription = stringResource(Res.string.book_detail_document_viewer_loading)

    val bitmapState by produceState<ImageBitmap?>(initialValue = null, index) {
        value =
            withContext(Dispatchers.Default) {
                renderMutex.withLock {
                    runCatching { wrapper.renderPage(index, THUMBNAIL_TARGET_WIDTH_PX).asImageBitmap() }.getOrNull()
                }
            }
    }

    val borderModifier =
        if (isCurrent) {
            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
        } else {
            Modifier
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onSelect(index) }
                .semantics { contentDescription = pageDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bitmap = bitmapState
        if (bitmap == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(A4_ASPECT_RATIO)
                        .then(borderModifier)
                        .semantics { contentDescription = loadingDescription },
            ) {
                // Placeholder — surfaceVariant fill while thumbnail renders.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {}
            }
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = pageDescription,
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(borderModifier),
            )
        }
        Text(
            text = stringResource(Res.string.book_reader_scrubber_page_count, index + 1),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
