package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.calypsan.listenup.client.design.util.fileLastModifiedMillis
import com.calypsan.listenup.core.IODispatcher
import kotlinx.coroutines.withContext

/**
 * Design system image component for local files.
 *
 * Includes intelligent cache-busting for local files - when a file is
 * modified, the cache key automatically updates based on the file's
 * last-modified timestamp.
 *
 * @param path Local file path to the image, or null for no image
 * @param contentDescription Accessibility description
 * @param modifier Optional modifier
 * @param contentScale How to scale the image (default: Crop)
 * @param refreshKey Optional key to force cache refresh (use when file is overwritten at same path)
 * @param onState Optional callback for loading state changes
 */
@Composable
fun ListenUpAsyncImage(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    refreshKey: Any? = null,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current

    // Async file check to avoid blocking main thread
    val cacheKey by produceState<String?>(
        initialValue = path,
        key1 = path,
        key2 = refreshKey,
    ) {
        value =
            withContext(IODispatcher) {
                path?.let { filePath ->
                    fileLastModifiedMillis(filePath)?.let { "$filePath:$it" } ?: filePath
                }
            }
    }

    val request =
        remember(cacheKey) {
            cacheKey?.let { key ->
                ImageRequest
                    .Builder(context)
                    .data(path)
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build()
            }
        }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onState = onState,
    )
}

/**
 * Variant that accepts a file:// prefixed path.
 *
 * Some parts of the codebase use "file://$path" format.
 * This normalizes the path before processing.
 */
@Composable
fun ListenUpAsyncImage(
    filePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    stripFilePrefix: Boolean = false,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val normalizedPath =
        if (stripFilePrefix && filePath?.startsWith("file://") == true) {
            filePath.removePrefix("file://")
        } else {
            filePath
        }

    ListenUpAsyncImage(
        path = normalizedPath,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onState = onState,
    )
}
