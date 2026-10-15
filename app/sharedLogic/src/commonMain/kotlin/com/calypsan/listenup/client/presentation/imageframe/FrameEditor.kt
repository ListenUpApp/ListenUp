package com.calypsan.listenup.client.presentation.imageframe

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.FrameEdit
import com.calypsan.listenup.client.core.image.NudgeDirection
import com.calypsan.listenup.client.core.image.canonical
import com.calypsan.listenup.client.core.image.nudgeFrame
import com.calypsan.listenup.client.core.image.panFrame
import com.calypsan.listenup.client.core.image.zoomFrameBy
import com.calypsan.listenup.client.core.image.zoomFrameTo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** The moment a gesture first runs into a limit: Android plays a haptic tick, iOS a light impact, web says "At edge". */
enum class FrameFeedback { EdgeReached, ZoomLimitReached }

/**
 * One frame-editing session over a decoded [imageWidth]×[imageHeight] photo. A plain state holder with no network:
 * every platform's gesture layer calls the same steps — [pan], [zoomBy], [zoomTo], [nudge], [reset] — in the editor's
 * own square units, measured through [setViewport], and every step runs the same clamp the photo is drawn with.
 *
 * Constructed only after a successful decode (a failed one is [FrameEditorState.CouldNotOpen], shown by the platform),
 * so a non-positive size is clamped to 1 rather than thrown. [feedback] fires on rising edges only: once when a gesture
 * first meets an edge or a zoom limit, not on every frame held there.
 */
class FrameEditor(
    imageWidth: Int,
    imageHeight: Int,
    initialFrame: ImageFrame?,
    shape: FrameShape,
) {
    private val width = imageWidth.coerceAtLeast(1)
    private val height = imageHeight.coerceAtLeast(1)
    private var viewport = 0.0
    private var atEdge = false
    private var atZoomLimit = false
    private val feedbackChannel = Channel<FrameFeedback>(Channel.BUFFERED)

    /** The frame being edited, always canonical, so it is exactly what Save stores. */
    val state: StateFlow<FrameEditorState.Ready>
        field =
        MutableStateFlow(
            FrameEditorState.Ready(
                imageWidth = width,
                imageHeight = height,
                frame = (initialFrame ?: ImageFrame.Centered).canonical(width.toDouble(), height.toDouble()),
                shape = shape,
            ),
        )

    /** One-shot limit events, on rising edges only. */
    val feedback: Flow<FrameFeedback> = feedbackChannel.receiveAsFlow()

    /**
     * [feedback] by each event's [FrameFeedback.name], for Swift: Swift Export traps casting the elements of a flow of
     * Kotlin enum entries. The same events, so a platform collects this or [feedback], never both.
     */
    val feedbackNames: Flow<String> = feedback.map { it.name }

    private val frame: ImageFrame get() = state.value.frame
    private val imageWidthUnits: Double get() = width.toDouble()
    private val imageHeightUnits: Double get() = height.toDouble()

    /** The editor's square side, in the units every gesture is given in. Call on first layout and every resize. */
    fun setViewport(size: Double) {
        viewport = size
    }

    /** Drag by ([dx], [dy]). */
    fun pan(
        dx: Double,
        dy: Double,
    ) {
        settle(
            panFrame(
                frame = frame,
                imageWidth = imageWidthUnits,
                imageHeight = imageHeightUnits,
                viewport = viewport,
                dx = dx,
                dy = dy,
            ),
        )
    }

    /** Pinch or wheel by [factor] about ([anchorX], [anchorY]). */
    fun zoomBy(
        factor: Double,
        anchorX: Double,
        anchorY: Double,
    ) {
        settle(
            zoomFrameBy(
                frame = frame,
                imageWidth = imageWidthUnits,
                imageHeight = imageHeightUnits,
                viewport = viewport,
                factor = factor,
                anchorX = anchorX,
                anchorY = anchorY,
            ),
        )
    }

    /** Zoom to [zoom] about the centre: the slider (through PR 2's log mapping) and the `+`/`-` keys. */
    fun zoomTo(zoom: Double) {
        settle(
            zoomFrameTo(
                frame = frame,
                imageWidth = imageWidthUnits,
                imageHeight = imageHeightUnits,
                viewport = viewport,
                zoom = zoom,
            ),
        )
    }

    /** One arrow-key or accessibility step; [NudgeDirection.Up] shows more of the photo's top. */
    fun nudge(
        direction: NudgeDirection,
        large: Boolean,
    ) {
        settle(
            nudgeFrame(
                frame = frame,
                imageWidth = imageWidthUnits,
                imageHeight = imageHeightUnits,
                viewport = viewport,
                direction = direction,
                large = large,
            ),
        )
    }

    /** Back to the centre crop. */
    fun reset() {
        atEdge = false
        atZoomLimit = false
        state.update { it.copy(frame = ImageFrame.Centered) }
    }

    private fun settle(edit: FrameEdit) {
        if (edit.hitEdge && !atEdge) feedbackChannel.trySend(FrameFeedback.EdgeReached)
        if (edit.hitZoomLimit && !atZoomLimit) feedbackChannel.trySend(FrameFeedback.ZoomLimitReached)
        atEdge = edit.hitEdge
        atZoomLimit = edit.hitZoomLimit
        state.update { it.copy(frame = edit.frame) }
    }
}
