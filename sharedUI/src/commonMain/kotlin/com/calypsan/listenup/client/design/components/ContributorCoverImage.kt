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

private fun contributorCacheKey(contributorId: String) = "$contributorId:contributor"

/**
 * Smart contributor image with server URL fallback.
 *
 * Loading strategy:
 * 1. If imagePath is provided -> pass directly to Coil (zero overhead, instant)
 * 2. If imagePath is null -> async check: local file exists? use disk. Otherwise server URL.
 *
 * Contributor images are NEVER blank when online.
 */
@Composable
fun ContributorCoverImage(
    contributorId: String,
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current

    // Fast path: imagePath provided means the file exists locally.
    val syncRequest =
        remember(contributorId, imagePath) {
            imagePath?.let {
                ImageRequest
                    .Builder(context)
                    .data(it)
                    .memoryCacheKey(contributorCacheKey(contributorId))
                    .diskCacheKey(contributorCacheKey(contributorId))
                    .build()
            }
        }

    // Slow path: no imagePath, need async resolution (check disk, fallback to server)
    val asyncRequest by produceState<ImageRequest?>(
        initialValue = null,
        key1 = contributorId,
        key2 = imagePath,
    ) {
        if (imagePath != null || contributorId.isBlank()) return@produceState

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
                val localPath = imageRepository.getContributorImagePath(contributorId)
                val exists = imageRepository.contributorImageExists(contributorId)

                if (exists) {
                    ImageRequest
                        .Builder(context)
                        .data(localPath)
                        .memoryCacheKey(contributorCacheKey(contributorId))
                        .diskCacheKey(contributorCacheKey(contributorId))
                        .build()
                } else {
                    val baseUrl = serverConfig.getActiveUrl()?.value
                    val token = authSession.getAccessToken()?.value
                    logger.debug {
                        "ContributorCoverImage: fallback id=$contributorId " +
                            "url=$baseUrl/api/v1/contributors/$contributorId/image"
                    }
                    if (baseUrl != null) {
                        ImageRequest
                            .Builder(context)
                            .data("$baseUrl/api/v1/contributors/$contributorId/image")
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
