package com.calypsan.listenup.client.core.image

import com.calypsan.listenup.api.dto.image.ImageFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Where to draw an image so it covers a viewport under an [ImageFrame]: draw it at ([offsetX], [offsetY]) with size
 * ([width], [height]) — [scale] times its intrinsic size — and clip to the viewport. Units are the viewport's own
 * (dp, points, pixels, or fractions on web, where 1.0 is the whole box).
 */
data class FrameLayout(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val width: Double,
    val height: Double,
)

/**
 * The one framing rule every platform draws with — Compose, SwiftUI (through Swift Export) and web. With no [frame] it
 * is exactly today's centre crop: `ContentScale.Crop`, `.fill`, `object-fit: cover`. Out-of-range values are clamped and
 * NaN maps to the middle of its range; nothing throws, and the image always covers the viewport. Every output is linear
 * in the viewport size, so one frame looks the same at 48, 120 and 140 points. A size that is not positive and finite
 * returns the viewport unscaled.
 */
fun frameLayout(
    imageWidth: Double,
    imageHeight: Double,
    viewportWidth: Double,
    viewportHeight: Double,
    frame: ImageFrame?,
): FrameLayout {
    if (!allDrawable(imageWidth, imageHeight, viewportWidth, viewportHeight)) {
        return FrameLayout(scale = 1.0, offsetX = 0.0, offsetY = 0.0, width = viewportWidth, height = viewportHeight)
    }
    val f = frame ?: ImageFrame.Centered
    val focusX = f.focusX.clampOrMid(0.0, 1.0)
    val focusY = f.focusY.clampOrMid(0.0, 1.0)
    val zoom = f.zoom.clampOrMid(ImageFrame.MIN_ZOOM, ImageFrame.MAX_ZOOM)
    val scale = max(viewportWidth / imageWidth, viewportHeight / imageHeight) * zoom
    val width = imageWidth * scale
    val height = imageHeight * scale
    // min(…, 0) keeps the range valid when floating point leaves the drawn size a hair under the viewport.
    val offsetX = (viewportWidth / 2 - focusX * width).coerceIn(min(viewportWidth - width, 0.0), 0.0)
    val offsetY = (viewportHeight / 2 - focusY * height).coerceIn(min(viewportHeight - height, 0.0), 0.0)
    return FrameLayout(scale, offsetX, offsetY, width, height)
}

/**
 * The frame as it is actually shown in any 1:1 shape: the focus that really sits at the centre once the clamp has
 * pinned an edge, and the zoom clamped into range. The editor always stores this, so what you see is what is saved and a
 * frame dragged hard against an edge hides no overshoot that would reappear at another size. A size that is not
 * positive and finite gives [ImageFrame.Centered].
 */
fun ImageFrame.canonical(
    imageWidth: Double,
    imageHeight: Double,
): ImageFrame {
    if (!allDrawable(imageWidth, imageHeight, 1.0, 1.0)) return ImageFrame.Centered
    val layout = frameLayout(imageWidth, imageHeight, 1.0, 1.0, this)
    return ImageFrame(
        focusX = (0.5 - layout.offsetX) / layout.width,
        focusY = (0.5 - layout.offsetY) / layout.height,
        zoom = zoom.clampOrMid(ImageFrame.MIN_ZOOM, ImageFrame.MAX_ZOOM),
    )
}

/** [coerceIn], except NaN — which no range contains — maps to the middle of the range. */
internal fun Double.clampOrMid(
    low: Double,
    high: Double,
): Double = if (isNaN()) (low + high) / 2 else coerceIn(low, high)

private fun allDrawable(vararg sizes: Double): Boolean = sizes.all { it > 0.0 && it.isFinite() }
