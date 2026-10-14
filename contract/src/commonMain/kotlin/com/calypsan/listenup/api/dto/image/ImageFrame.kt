package com.calypsan.listenup.api.dto.image

import com.calypsan.listenup.api.error.ValidationError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The input field every frame rejection names, so a form can point at the photo without parsing a message. */
const val IMAGE_FRAME_FIELD: String = "imageFrame"

/** The multipart form field an image upload carries its [ImageFrame] JSON in, beside the file part. */
const val IMAGE_UPLOAD_FRAME_PART: String = "frame"

private const val INVALID_FRAME_MESSAGE = "That photo position couldn't be saved."

/**
 * How a photo sits inside its display shape: the point of the image that should sit at the shape's centre
 * ([focusX], [focusY], each 0..1 of the image's width and height) and how far to zoom past "just covers the shape"
 * ([zoom], [MIN_ZOOM]..[MAX_ZOOM]). Shape-agnostic: one frame serves every 1:1 shape (circle, scallop, square).
 *
 * No `init` check on purpose: an editor builds an intermediate frame on every drag frame, and a throwing constructor
 * there is a crash. Layout clamps out-of-range values; the server rejects them through [validate].
 */
@Serializable
@SerialName("ImageFrame")
data class ImageFrame(
    @SerialName("focusX") val focusX: Double,
    @SerialName("focusY") val focusY: Double,
    @SerialName("zoom") val zoom: Double,
) {
    /** The zoom range, and the frame that means "no framing". */
    companion object {
        /** The photo just covers the shape. */
        const val MIN_ZOOM: Double = 1.0

        /** Four times past covering. */
        const val MAX_ZOOM: Double = 4.0

        /** Today's centre crop, expressed as a frame. */
        val Centered: ImageFrame = ImageFrame(focusX = 0.5, focusY = 0.5, zoom = MIN_ZOOM)
    }
}

/**
 * An [ImageFrame] bound to the exact image it was made for. [imageVersion] is that image's version token: the
 * lowercase SHA-256 hex of a contributor photo (the stem of its content-addressed path, see [contentVersionOf]) or an
 * avatar's `avatarUpdatedAt` as a decimal string. A framing whose version is not the current image's is ignored.
 */
@Serializable
@SerialName("ImageFraming")
data class ImageFraming(
    @SerialName("imageVersion") val imageVersion: String,
    @SerialName("frame") val frame: ImageFrame,
) {
    /** Bounds on [imageVersion]. */
    companion object {
        /** Longest accepted version token. A SHA-256 hex is 64 characters and an epoch-ms stamp 13. */
        const val MAX_IMAGE_VERSION_LENGTH: Int = 128
    }
}

/** The one rejection every invalid frame gets: the constant message, [IMAGE_FRAME_FIELD], and [debugInfo] for logs. */
fun invalidImageFrame(debugInfo: String? = null): ValidationError =
    ValidationError(message = INVALID_FRAME_MESSAGE, debugInfo = debugInfo, field = IMAGE_FRAME_FIELD)

/** Null when both focuses are in 0..1 and the zoom in [ImageFrame.MIN_ZOOM]..[ImageFrame.MAX_ZOOM]; NaN is never in range. */
fun ImageFrame.validate(): ValidationError? =
    if (focusX in 0.0..1.0 && focusY in 0.0..1.0 && zoom in ImageFrame.MIN_ZOOM..ImageFrame.MAX_ZOOM) {
        null
    } else {
        invalidImageFrame("focus=($focusX, $focusY) zoom=$zoom")
    }

/** [ImageFrame.validate], plus a non-blank [ImageFraming.imageVersion] of at most [ImageFraming.MAX_IMAGE_VERSION_LENGTH]. */
fun ImageFraming.validate(): ValidationError? =
    frame.validate() ?: if (imageVersion.isBlank() || imageVersion.length > ImageFraming.MAX_IMAGE_VERSION_LENGTH) {
        invalidImageFrame("imageVersion length ${imageVersion.length}")
    } else {
        null
    }

/** The version token of a content-addressed image path: `contributors/<sha>.jpg` → `<sha>`. */
fun contentVersionOf(imagePath: String): String = imagePath.substringAfterLast('/').substringBeforeLast('.')

/** Whether this framing was made for the image at [imagePath]. Never true for no image. */
fun ImageFraming.isFor(imagePath: String?): Boolean = imagePath != null && imageVersion == contentVersionOf(imagePath)
