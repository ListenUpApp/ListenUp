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
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

private fun seriesCacheKey(seriesId: String) = "$seriesId:series-cover"

/**
 * Smart series cover image with server URL fallback.
 *
 * Loading strategy:
 * 1. If [coverPath] is provided → pass directly to Coil (zero overhead, instant).
 * 2. If [coverPath] is null → async check: local file exists? use disk. Otherwise server URL.
 *
 * The server route for series covers is `GET /api/v1/series/{id}/cover`.
 */
@Composable
fun SeriesCoverImage(
    seriesId: String,
    coverPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current

    // Fast path: coverPath provided means the file exists locally.
    val syncRequest =
        remember(seriesId, coverPath) {
            coverPath?.let {
                ImageRequest
                    .Builder(context)
                    .data(it)
                    .memoryCacheKey(seriesCacheKey(seriesId))
                    .diskCacheKey(seriesCacheKey(seriesId))
                    .build()
            }
        }

    // Slow path: no coverPath, need async resolution (check disk, fallback to server)
    val asyncRequest by produceState<ImageRequest?>(
        initialValue = null,
        key1 = seriesId,
        key2 = coverPath,
    ) {
        if (coverPath != null || seriesId.isBlank()) return@produceState

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
                val localPath = imageRepository.getSeriesCoverPath(seriesId)
                val exists = imageRepository.seriesCoverExists(seriesId)

                if (exists) {
                    ImageRequest
                        .Builder(context)
                        .data(localPath)
                        .memoryCacheKey(seriesCacheKey(seriesId))
                        .diskCacheKey(seriesCacheKey(seriesId))
                        .build()
                } else {
                    val baseUrl = serverConfig.getActiveUrl()?.value
                    val token = authSession.getAccessToken()?.value
                    logger.debug {
                        "SeriesCoverImage: fallback id=$seriesId " +
                            "url=$baseUrl/api/v1/series/$seriesId/cover"
                    }
                    if (baseUrl != null) {
                        ImageRequest
                            .Builder(context)
                            .data("$baseUrl/api/v1/series/$seriesId/cover")
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

    imageRequest?.let {
        AsyncImage(
            model = it,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            onState = onState,
        )
    }
}
