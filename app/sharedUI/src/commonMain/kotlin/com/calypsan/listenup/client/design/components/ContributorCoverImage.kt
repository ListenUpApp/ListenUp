package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.design.image.FramedAlignment
import com.calypsan.listenup.client.design.image.FramedCrop
import com.calypsan.listenup.client.design.image.rememberZoomedSizeResolver
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.design.transitions.heroElement
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
 * 1. If stagingImagePath is provided -> render that local file directly (edit-preview fast path,
 *    mirroring [BookCoverImage]'s provided-coverPath path).
 * 2. If imagePath is provided -> pass directly to Coil (zero overhead, instant)
 * 3. If imagePath is null -> async check: local file exists? use disk. Otherwise server URL.
 *
 * Contributor images are NEVER blank when online.
 *
 * @param stagingImagePath Absolute local path of a picked-but-unsaved image to preview. When
 *   non-null it wins over the resolved photo so the edit screen shows the selection immediately.
 * @param frame How the photo sits in its shape; null is the centre crop.
 */
@Composable
fun ContributorCoverImage(
    contributorId: String,
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    heroKey: Any? = null,
    heroClipShape: Shape? = null,
    stagingImagePath: String? = null,
    frame: ImageFrame? = null,
    contentScale: ContentScale = contributorContentScale(frame),
    alignment: Alignment = contributorAlignment(frame),
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val (constraints, zoomed) = rememberZoomedSizeResolver(frame)

    // Staging fast path: a picked-but-unsaved local file renders directly, so the edit-screen
    // preview appears before Save. Mirrors [BookCoverImage]'s provided-coverPath fast path.
    if (stagingImagePath != null) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(context)
                    .data(stagingImagePath)
                    .size(zoomed)
                    .build(),
            contentDescription = contentDescription,
            modifier = modifier.then(constraints).heroElement(heroKey, heroClipShape),
            contentScale = contentScale,
            alignment = alignment,
            onState = onState,
        )
        return
    }

    val imageRequest = rememberContributorImageRequest(contributorId, imagePath) { size(zoomed) }

    imageRequest?.let { request ->
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier.then(constraints).heroElement(heroKey, heroClipShape),
            contentScale = contentScale,
            alignment = alignment,
            onState = onState,
        )
    }
}

/** The scale a contributor photo uses for [frame]; [ContentScale.Crop] when it is null. */
internal fun contributorContentScale(frame: ImageFrame?): ContentScale = FramedCrop(frame)

/** The alignment a contributor photo uses for [frame]; centred when it is null. */
internal fun contributorAlignment(frame: ImageFrame?): Alignment = FramedAlignment(frame)

/**
 * The request that loads a contributor's photo: the durable local file when cached, otherwise the
 * authenticated, version-busted server stream. Null until resolved. Shared by [ContributorCoverImage]
 * and the Adjust photo editor, so both open the same bytes.
 *
 * [imagePath] is the server's content-addressed *relative* path (e.g. "contributors/<sha>.jpg") or
 * null; it is NOT a locally loadable source, only a cache-busting version key. The bytes are always
 * resolved here: the locally cached file when present, otherwise the server `/photo` stream (kicking
 * off a background cache for offline use). Handing the relative path straight to Coil was the bug
 * that left scraped contributor photos blank.
 */
@Composable
fun rememberContributorImageRequest(
    contributorId: String,
    imagePath: String?,
    configure: ImageRequest.Builder.() -> Unit = {},
): ImageRequest? {
    val context = LocalPlatformContext.current
    val currentConfigure by rememberUpdatedState(configure)
    val request by produceState<ImageRequest?>(
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
                builder.currentConfigure()

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
                                imagePath?.let { "?v=$it" }.orEmpty()
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
    return request
}
