package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.util.bookCoverCacheKey
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.IODispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Smart book cover image with server URL fallback.
 *
 * Loading strategy:
 * 1. If coverPath is provided -> pass directly to Coil (zero overhead, instant)
 * 2. If coverPath is null -> async check: local file exists? use disk. Otherwise server URL.
 * 3. Once the download queue saves the cover to disk, subsequent renders use disk.
 *
 * When no cover can be resolved (or it fails to load) and [title] is provided, the gradient
 * [BookCoverFallback] is shown instead of a blank surface. When [title] is null the behavior is
 * unchanged: the cover renders, or nothing does.
 */
@Composable
fun BookCoverImage(
    bookId: String,
    coverPath: String?,
    contentDescription: String?,
    title: String? = null,
    author: String? = null,
    modifier: Modifier = Modifier,
    coverHash: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val imageRequest = rememberCoverRequest(bookId, coverPath, coverHash)

    var showFallback by remember(bookId, coverPath) { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Bottom layer: gradient placeholder when there's no image to show or it failed to load.
        if (title != null && (imageRequest == null || showFallback)) {
            BookCoverFallback(
                title = title,
                author = author.orEmpty(),
                modifier = Modifier.matchParentSize(),
                seed = bookId,
            )
        }

        // Top layer: the actual cover. A success covers the fallback; an error reveals it.
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) {
                        showFallback = false
                    } else if (state is AsyncImagePainter.State.Error) {
                        showFallback = true
                    }
                    onState?.invoke(state)
                },
            )
        }
    }
}

/**
 * Resolves the cover [ImageRequest] for [bookId]/[coverPath]: the synchronous local-path request when
 * one is given, otherwise an async check for a cached file falling back to the authenticated server URL.
 * Returns null until resolution settles or when there is no source at all (offline, no cache, no base URL).
 */
@Composable
private fun rememberCoverRequest(
    bookId: String,
    coverPath: String?,
    coverHash: String?,
): ImageRequest? {
    val context = LocalPlatformContext.current
    val cacheKey = bookCoverCacheKey(bookId, coverHash)

    // Fast path: coverPath provided means the file exists locally.
    // Build the request synchronously — no IO dispatch, no frame delay.
    val syncRequest =
        remember(bookId, coverPath, coverHash) {
            coverPath?.let {
                ImageRequest
                    .Builder(context)
                    .data(it)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build()
            }
        }

    // Slow path: no coverPath, need async resolution (check disk, fallback to server).
    val asyncRequest: State<ImageRequest?> =
        produceState(initialValue = null, key1 = bookId, key2 = coverPath, key3 = coverHash) {
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
                withContext(IODispatcher) {
                    val localPath = imageRepository.getBookCoverPath(BookId(bookId))
                    if (imageRepository.bookCoverExists(BookId(bookId))) {
                        ImageRequest
                            .Builder(context)
                            .data(localPath)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey(cacheKey)
                            .build()
                    } else {
                        // No durable file yet — kick off a background download so this streamed
                        // cover is persisted on disk for offline use, then stream from the server now.
                        imageRepository.ensureBookCoverCached(BookId(bookId))
                        serverCoverRequest(context, bookId, serverConfig, authSession, localPath, cacheKey, coverHash)
                    }
                }
        }

    return syncRequest ?: asyncRequest.value
}

/**
 * Builds the authenticated server cover request, falling back to the local path when no server URL is
 * configured (offline before any onboarding).
 *
 * [cacheKey] folds the book's coverHash into Coil's memory/disk key so a re-covered book (new hash)
 * busts the cached image instead of serving the stale one. Without it Coil keys on the cover URL —
 * which never changes for a given book — so a replaced cover (e.g. a wizard-applied Audible cover)
 * keeps rendering the old image, and the disk cache survives even an app restart.
 */
private suspend fun serverCoverRequest(
    context: coil3.PlatformContext,
    bookId: String,
    serverConfig: ServerConfig,
    authSession: AuthSession,
    localPath: String,
    cacheKey: String,
    coverHash: String?,
): ImageRequest {
    val baseUrl = serverConfig.getActiveUrl()?.value
    val token = authSession.getAccessToken()?.value
    logger.debug { "BookCoverImage: fallback bookId=$bookId, url=$baseUrl/api/v1/covers/$bookId" }
    return if (baseUrl != null) {
        // Content-address the URL with the coverHash so a re-covered book changes the URL itself —
        // busting EVERY cache layer (Coil disk + the platform HTTP cache), not just Coil's key. The
        // server ignores the `?v` param.
        ImageRequest
            .Builder(context)
            .data("$baseUrl/api/v1/covers/$bookId" + (coverHash?.let { "?v=$it" } ?: ""))
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .apply {
                if (token != null) {
                    httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $token").build())
                }
            }.build()
    } else {
        ImageRequest
            .Builder(context)
            .data(localPath)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }
}
