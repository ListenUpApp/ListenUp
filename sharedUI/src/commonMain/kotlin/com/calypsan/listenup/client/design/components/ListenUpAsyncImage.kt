package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.calypsan.listenup.client.design.util.decodeBlurHash
import com.calypsan.listenup.client.design.util.fileLastModifiedMillis
import com.calypsan.listenup.core.IODispatcher
import kotlinx.coroutines.withContext

/**
 * Design system image component with BlurHash placeholder support.
 *
 * When a blurHash is provided:
 * 1. Shows a gray background immediately
 * 2. Renders the BlurHash as a blurry placeholder
 * 3. Fades in the real image when loaded
 *
 * Also includes intelligent cache-busting for local files - when a file is
 * modified, the cache key automatically updates based on the file's
 * last-modified timestamp.
 *
 * @param path Local file path to the image, or null for no image
 * @param contentDescription Accessibility description
 * @param modifier Optional modifier
 * @param blurHash BlurHash string for placeholder, or null for no placeholder
 * @param contentScale How to scale the image (default: Crop)
 * @param refreshKey Optional key to force cache refresh (use when file is overwritten at same path)
 * @param onState Optional callback for loading state changes
 */
@Composable
fun ListenUpAsyncImage(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    blurHash: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    refreshKey: Any? = null,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    var imageLoaded by remember { mutableStateOf(false) }

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

    // When blurHash is provided, use layered rendering with placeholder
    if (blurHash != null) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            // Layer 1: BlurHash placeholder (instant, shows until real image loads)
            if (!imageLoaded) {
                BlurHashPlaceholder(
                    blurHash = blurHash,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Layer 2: Real image (loads on top of BlurHash, which fades out when loaded)
            if (path != null) {
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) {
                            imageLoaded = true
                        }
                        onState?.invoke(state)
                    },
                )
            }
        }
    } else {
        // No blurHash - simple image loading without placeholder
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            onState = onState,
        )
    }
}

/**
 * Renders a BlurHash as a placeholder image.
 *
 * BlurHash is decoded to a small bitmap (32x32) and scaled up by Compose.
 * Decoding is cached per blurHash string and takes <1ms.
 */
@Composable
private fun BlurHashPlaceholder(
    blurHash: String,
    modifier: Modifier = Modifier,
) {
    val bitmap: ImageBitmap? =
        remember(blurHash) {
            decodeBlurHash(blurHash, width = 32, height = 32)
        }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
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
    blurHash: String? = null,
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
        blurHash = blurHash,
        contentScale = contentScale,
        onState = onState,
    )
}
