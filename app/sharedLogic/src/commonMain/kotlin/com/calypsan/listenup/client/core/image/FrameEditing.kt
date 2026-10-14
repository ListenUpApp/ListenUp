package com.calypsan.listenup.client.core.image

import com.calypsan.listenup.api.dto.image.ImageFrame
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One step of the `+`/`-` keys and of the accessibility zoom actions. */
const val ZOOM_STEP: Double = 1.15

private const val NUDGE_FRACTION = 0.05
private const val LARGE_NUDGE_FRACTION = 0.20
private const val WHEEL_ZOOM_RATE = 0.002

/** How far past an edge, per pixel of drawn size, a clamped offset may sit before it counts as hitting it. */
private const val EDGE_SLACK = 1e-9

/** Likewise for a zoom landing exactly on [ImageFrame.MIN_ZOOM] or [ImageFrame.MAX_ZOOM]. */
private const val ZOOM_SLACK = 1e-9

/**
 * One editing gesture's result: the new [frame], already canonical (what you see is what is saved), and whether the
 * gesture was stopped by an edge ([hitEdge]) or by the zoom range ([hitZoomLimit]).
 */
data class FrameEdit(
    val frame: ImageFrame,
    val hitEdge: Boolean,
    val hitZoomLimit: Boolean,
)

/**
 * Which way a nudge moves the view. [Up] shows more of the photo's top — the view moves up, as a page scrolls — and so
 * on. Decided call: "move up" shows more of the image's top.
 */
enum class NudgeDirection { Up, Down, Left, Right }

/** Drags the photo by ([dx], [dy]) viewport units inside a square editor [viewport]. */
fun panFrame(
    frame: ImageFrame,
    imageWidth: Double,
    imageHeight: Double,
    viewport: Double,
    dx: Double,
    dy: Double,
): FrameEdit {
    if (!editable(imageWidth, imageHeight, viewport) || !dx.isFinite() || !dy.isFinite()) return unchanged(frame)
    val layout =
        frameLayout(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            viewportWidth = viewport,
            viewportHeight = viewport,
            frame = frame,
        )
    return place(
        offsetX = layout.offsetX + dx,
        offsetY = layout.offsetY + dy,
        width = layout.width,
        height = layout.height,
        viewport = viewport,
        zoom = frame.sanitisedZoom(),
        hitZoomLimit = false,
    )
}

/**
 * Zooms by [factor] about ([anchorX], [anchorY]) in viewport units — the pinch centroid or the wheel's cursor. The point
 * under the anchor stays put unless an edge or the zoom range stops it.
 */
fun zoomFrameBy(
    frame: ImageFrame,
    imageWidth: Double,
    imageHeight: Double,
    viewport: Double,
    factor: Double,
    anchorX: Double,
    anchorY: Double,
): FrameEdit {
    val gestureIsReal = factor > 0.0 && factor.isFinite() && anchorX.isFinite() && anchorY.isFinite()
    if (!editable(imageWidth, imageHeight, viewport) || !gestureIsReal) return unchanged(frame)
    val layout =
        frameLayout(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            viewportWidth = viewport,
            viewportHeight = viewport,
            frame = frame,
        )
    val target = frame.sanitisedZoom() * factor
    val zoom = target.coerceIn(ImageFrame.MIN_ZOOM, ImageFrame.MAX_ZOOM)
    val hitZoomLimit = target < ImageFrame.MIN_ZOOM - ZOOM_SLACK || target > ImageFrame.MAX_ZOOM + ZOOM_SLACK
    val pointX = (anchorX - layout.offsetX) / layout.width
    val pointY = (anchorY - layout.offsetY) / layout.height
    val cover = max(viewport / imageWidth, viewport / imageHeight)
    val width = imageWidth * cover * zoom
    val height = imageHeight * cover * zoom
    return place(
        offsetX = anchorX - pointX * width,
        offsetY = anchorY - pointY * height,
        width = width,
        height = height,
        viewport = viewport,
        zoom = zoom,
        hitZoomLimit = hitZoomLimit,
    )
}

/** Zooms to [zoom] about the editor's centre — the slider and the `+`/`-` keys. */
fun zoomFrameTo(
    frame: ImageFrame,
    imageWidth: Double,
    imageHeight: Double,
    viewport: Double,
    zoom: Double,
): FrameEdit =
    if (!zoom.isFinite()) {
        unchanged(frame)
    } else {
        zoomFrameBy(
            frame = frame,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            viewport = viewport,
            factor = zoom / frame.sanitisedZoom(),
            anchorX = viewport / 2,
            anchorY = viewport / 2,
        )
    }

/** One arrow-key or accessibility step: 5% of the [viewport], or 20% when [large]. */
fun nudgeFrame(
    frame: ImageFrame,
    imageWidth: Double,
    imageHeight: Double,
    viewport: Double,
    direction: NudgeDirection,
    large: Boolean,
): FrameEdit {
    val step = viewport * if (large) LARGE_NUDGE_FRACTION else NUDGE_FRACTION
    val (dx, dy) =
        when (direction) {
            NudgeDirection.Up -> 0.0 to step
            NudgeDirection.Down -> 0.0 to -step
            NudgeDirection.Left -> step to 0.0
            NudgeDirection.Right -> -step to 0.0
        }
    return panFrame(
        frame = frame,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        viewport = viewport,
        dx = dx,
        dy = dy,
    )
}

/** The zoom factor for one wheel event: a negative [deltaY] (scrolling up) zooms in. */
fun wheelZoomFactor(deltaY: Double): Double = if (deltaY.isFinite()) exp(-deltaY * WHEEL_ZOOM_RATE) else 1.0

private fun editable(
    imageWidth: Double,
    imageHeight: Double,
    viewport: Double,
): Boolean = listOf(imageWidth, imageHeight, viewport).all { it > 0.0 && it.isFinite() }

private fun unchanged(frame: ImageFrame) = FrameEdit(frame, hitEdge = false, hitZoomLimit = false)

private fun ImageFrame.sanitisedZoom(): Double = zoom.clampOrMid(ImageFrame.MIN_ZOOM, ImageFrame.MAX_ZOOM)

/** Clamps a proposed placement so the image covers the editor, and reads the canonical frame back off it. */
private fun place(
    offsetX: Double,
    offsetY: Double,
    width: Double,
    height: Double,
    viewport: Double,
    zoom: Double,
    hitZoomLimit: Boolean,
): FrameEdit {
    val minX = min(viewport - width, 0.0)
    val minY = min(viewport - height, 0.0)
    val slackX = EDGE_SLACK * width
    val slackY = EDGE_SLACK * height
    val hitEdge = offsetX > slackX || offsetX < minX - slackX || offsetY > slackY || offsetY < minY - slackY
    val x = offsetX.coerceIn(minX, 0.0)
    val y = offsetY.coerceIn(minY, 0.0)
    return FrameEdit(ImageFrame((viewport / 2 - x) / width, (viewport / 2 - y) / height, zoom), hitEdge, hitZoomLimit)
}
