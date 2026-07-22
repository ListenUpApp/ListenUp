package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.calypsan.listenup.core.IODispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

// Folds the content-addressed [imagePath] into the key so a re-scraped photo (new path) busts the
// Coil cache instead of serving the cached old one. Null path → the legacy per-contributor key.
private fun contributorCacheKey(
    contributorId: String,
    imagePath: String?,
) = "$contributorId:${imagePath ?: "contributor"}"

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

    // [imagePath] is the server's content-addressed *relative* path (e.g. "contributors/<sha>.jpg")
    // or null — it is NOT a locally loadable source, only a cache-busting version key. We always
    // resolve the bytes ourselves: the locally cached file when present, otherwise the server
    // `/photo` stream (kicking off a background cache for offline use). Handing the relative path
    // straight to Coil was the bug that left scraped contributor photos blank.
    val imageRequest by produceState<ImageRequest?>(
        initialValue = null,
        key1 = contributorId,
        key2 = imagePath,
    ) {
        if (contributorId.isBlank()) return@produceState

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
                val builder =
                    ImageRequest
                        .Builder(context)
                        .memoryCacheKey(contributorCacheKey(contributorId, imagePath))
                        .diskCacheKey(contributorCacheKey(contributorId, imagePath))

                val localPath = imageRepository.getContributorImagePath(contributorId)
                if (imageRepository.contributorImageExists(contributorId)) {
                    builder.data(localPath).build()
                } else {
                    // No durable file yet — kick off a background download so the photo persists for
                    // offline use, then stream it now from the server with the auth token.
                    imageRepository.ensureContributorImageCached(contributorId)
                    val baseUrl = serverConfig.getActiveUrl()?.value
                    if (baseUrl != null) {
                        val token = authSession.getAccessToken()?.value
                        logger.debug {
                            "ContributorCoverImage: streaming id=$contributorId " +
                                "url=$baseUrl/api/v1/contributors/$contributorId/photo"
                        }
                        // Content-address the URL with the server's imagePath (the photo's version) so
                        // a re-scrape changes the URL itself — this busts EVERY cache layer (Coil disk +
                        // the platform HTTP cache), not just Coil's key. The server ignores the `?v` param.
                        val versioned =
                            "$baseUrl/api/v1/contributors/$contributorId/photo" +
                                (imagePath?.let { "?v=$it" } ?: "")
                        builder.data(versioned)
                        if (token != null) {
                            builder.httpHeaders(
                                NetworkHeaders
                                    .Builder()
                                    .set("Authorization", "Bearer $token")
                                    .build(),
                            )
                        }
                        builder.build()
                    } else {
                        builder.data(localPath).build()
                    }
                }
            }
    }

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
