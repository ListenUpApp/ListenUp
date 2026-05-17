package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.design.util.decodeBlurHash
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import com.calypsan.listenup.client.core.Success

private val logger = KotlinLogging.logger {}

/**
 * Smart book cover image with server URL fallback.
 *
 * Loading strategy:
 * 1. If coverPath is provided -> pass directly to Coil (zero overhead, instant)
 * 2. If coverPath is null -> async check: local file exists? use disk. Otherwise server URL.
 * 3. Once the download queue saves the cover to disk, subsequent renders use disk.
 *
 * Covers are NEVER blank when online.
 */
@Composable
fun BookCoverImage(
    bookId: String,
    coverPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    blurHash: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current

    // Fast path: coverPath provided means the file exists locally.
    // Build the request synchronously — no IO dispatch, no frame delay.
    val syncRequest =
        remember(bookId, coverPath) {
            if (coverPath != null) {
                ImageRequest
                    .Builder(context)
                    .data(coverPath)
                    .memoryCacheKey("$bookId:cover")
                    .diskCacheKey("$bookId:cover")
                    .build()
            } else {
                null
            }
        }

    // Slow path: no coverPath, need async resolution (check disk, fallback to server)
    val asyncRequest by produceState<ImageRequest?>(
        initialValue = null,
        key1 = bookId,
        key2 = coverPath,
    ) {
        // Skip async if we already have a sync request, or if bookId is blank (no valid ID to look up)
        if (coverPath != null || bookId.isBlank()) return@produceState

        val imageRepository: ImageRepository =
            org.koin.core.context.GlobalContext
                .get()
                .get()
        val serverConfig: ServerConfig =
            org.koin.core.context.GlobalContext
                .get()
                .get()
        val authSession: AuthSession =
            org.koin.core.context.GlobalContext
                .get()
                .get()

        value =
            withContext(Dispatchers.IO) {
                val localPath = imageRepository.getBookCoverPath(BookId(bookId))
                val exists = imageRepository.bookCoverExists(BookId(bookId))

                if (exists) {
                    ImageRequest
                        .Builder(context)
                        .data(localPath)
                        .memoryCacheKey("$bookId:cover")
                        .diskCacheKey("$bookId:cover")
                        .build()
                } else {
                    val baseUrl = serverConfig.getActiveUrl()?.value
                    val token = authSession.getAccessToken()?.value
                    logger.debug { "BookCoverImage: fallback bookId=$bookId, url=$baseUrl/api/v1/covers/$bookId" }
                    if (baseUrl != null) {
                        ImageRequest
                            .Builder(context)
                            .data("$baseUrl/api/v1/covers/$bookId")
                            .apply {
                                if (token != null) {
                                    httpHeaders(
                                        NetworkHeaders
                                            .Builder()
                                            .set("Authorization", "Bearer $token")
                                            .build(),
                                    )
                                }
                            }.build()
                    } else {
                        ImageRequest
                            .Builder(context)
                            .data(localPath)
                            .build()
                    }
                }
            }
    }

    val imageRequest = syncRequest ?: asyncRequest

    if (blurHash != null) {
        var imageLoaded by remember(bookId) { mutableStateOf(false) }

        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (!imageLoaded) {
                val bitmap: ImageBitmap? =
                    remember(blurHash) {
                        decodeBlurHash(blurHash, width = 32, height = 32)
                    }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
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
    } else if (imageRequest != null) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            onState = onState,
        )
    }
}
