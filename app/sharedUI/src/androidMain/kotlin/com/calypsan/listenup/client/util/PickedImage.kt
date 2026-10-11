package com.calypsan.listenup.client.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.calypsan.listenup.client.playback.computeInSampleSize
import com.calypsan.listenup.core.IODispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val logger = KotlinLogging.logger {}

/** Longest edge, in pixels, a picked image is decoded to before upload. */
internal const val PICKED_IMAGE_MAX_EDGE_PX = 2048

/** Power-of-two sample factor that brings a picked image within [PICKED_IMAGE_MAX_EDGE_PX]. */
internal fun pickedImageSampleSize(
    srcWidth: Int,
    srcHeight: Int,
): Int = computeInSampleSize(srcWidth, srcHeight, PICKED_IMAGE_MAX_EDGE_PX)

/**
 * Decodes the image at [uri] to an upright JPEG no larger than [PICKED_IMAGE_MAX_EDGE_PX] on its
 * longest edge, or null when it cannot be opened or decoded.
 *
 * [ImageDecoder] applies the EXIF orientation the camera recorded, which a plain re-encode would
 * discard and upload the photo sideways. It also downsamples while decoding, so a large photo is
 * never held at full size. Runs on [IODispatcher]; safe to call from the main thread.
 */
internal suspend fun decodePickedImageAsJpeg(
    context: Context,
    uri: Uri,
    quality: Int,
): ByteArray? =
    withContext(IODispatcher) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap =
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setTargetSampleSize(pickedImageSampleSize(info.size.width, info.size.height))
                    // A hardware bitmap cannot be compressed.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            try {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException (incl. ImageDecoder.DecodeException) for an unreadable image, plus the
            // SecurityException/IllegalArgumentException a content provider may raise for a stale URI.
            // Log the authority only, never the query.
            logger.warn(e) { "Could not decode picked image from ${uri.authority.orEmpty()}" }
            null
        }
    }
