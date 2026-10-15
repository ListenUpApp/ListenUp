package com.calypsan.listenup.client.design.image

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.NudgeDirection
import com.calypsan.listenup.client.core.image.ZOOM_STEP
import com.calypsan.listenup.client.core.image.frameLayout
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.imageframe.FrameEditor
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.edit_frame_hint
import listenup.composeapp.generated.resources.edit_frame_photo_position
import listenup.composeapp.generated.resources.edit_frame_reset
import listenup.composeapp.generated.resources.edit_frame_show_bottom
import listenup.composeapp.generated.resources.edit_frame_show_left
import listenup.composeapp.generated.resources.edit_frame_show_right
import listenup.composeapp.generated.resources.edit_frame_show_top
import listenup.composeapp.generated.resources.edit_frame_zoom_in
import listenup.composeapp.generated.resources.edit_frame_zoom_out
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

/** How far the cut-out sits inside the stage's shorter side: the phone board's 45 dp, the tablet dialog's 32 dp. */
private val PHONE_CUT_OUT_INSET = 45.dp
private val DIALOG_CUT_OUT_INSET = 32.dp

/** The dimming over the photo outside the cut-out: enough to read the cut-out, light enough to keep context. */
private const val SCRIM_ALPHA = 0.62f
private const val HINT_ALPHA = 0.55f
private val CUT_OUT_RING = 2.dp
private val SQUARE_CORNER = 12.dp
private val HINT_BOTTOM_GAP = 20.dp

/**
 * The editor's stage: the whole photo, dimmed outside a cut-out that shows exactly what every avatar and
 * contributor photo will show. Drag pans and pinch zooms through [editor]; TalkBack gets the same moves as
 * custom actions. The stage is black with a white ring in both themes (Decision 7): a photo is judged
 * against neutral black, and a theme-following ring would vanish in dark mode.
 */
@Composable
internal fun FrameStage(
    editor: FrameEditor,
    image: FrameEditorImage.Ready,
    frame: ImageFrame,
    shape: FrameShape,
    isDialog: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    LaunchedEffect(editor) { editor.feedback.collect { haptics.selectionTick() } }

    val scrim = MaterialTheme.colorScheme.scrim
    val actions = rememberStageActions(editor, frame)
    val positionLabel = stringResource(Res.string.edit_frame_photo_position)

    BoxWithConstraints(modifier = modifier.clipToBounds().background(scrim)) {
        val density = LocalDensity.current
        val inset = if (isDialog) DIALOG_CUT_OUT_INSET else PHONE_CUT_OUT_INSET
        val stageWidth = constraints.maxWidth
        val stageHeight = constraints.maxHeight
        val diameter = (min(stageWidth, stageHeight) - 2 * with(density) { inset.roundToPx() }).coerceAtLeast(1)
        val cutLeft = (stageWidth - diameter) / 2f
        val cutTop = (stageHeight - diameter) / 2f
        SideEffect { editor.setViewport(diameter.toDouble()) }

        val layout =
            frameLayout(
                image.width.toDouble(),
                image.height.toDouble(),
                diameter.toDouble(),
                diameter.toDouble(),
                frame,
            )
        // The photo is drawn across the whole stage, positioned as the cut-out will show it, so the dimmed
        // surround keeps the context a pinch needs.
        Image(
            painter = image.painter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier =
                Modifier.layout { measurable, _ ->
                    val placeable =
                        measurable.measure(
                            Constraints.fixed(
                                layout.width.roundToInt().coerceAtLeast(1),
                                layout.height.roundToInt().coerceAtLeast(1),
                            ),
                        )
                    layout(stageWidth, stageHeight) {
                        placeable.place(
                            (cutLeft + layout.offsetX).roundToInt(),
                            (cutTop + layout.offsetY).roundToInt(),
                        )
                    }
                },
        )

        Canvas(modifier = Modifier.matchParentSize()) {
            val cut = Rect(Offset(cutLeft, cutTop), Size(diameter.toFloat(), diameter.toFloat()))
            val hole =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    when (shape) {
                        FrameShape.Circle -> addOval(cut)
                        FrameShape.Square -> addRoundRect(RoundRect(cut, CornerRadius(SQUARE_CORNER.toPx())))
                    }
                }
            drawPath(hole, scrim.copy(alpha = SCRIM_ALPHA))
            val ring = Stroke(CUT_OUT_RING.toPx())
            when (shape) {
                FrameShape.Circle -> {
                    drawCircle(Color.White, radius = diameter / 2f, center = cut.center, style = ring)
                }

                FrameShape.Square -> {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = cut.topLeft,
                        size = cut.size,
                        cornerRadius = CornerRadius(SQUARE_CORNER.toPx()),
                        style = ring,
                    )
                }
            }
        }

        // The gesture and accessibility surface covers the whole stage: a drag that starts outside the
        // cut-out still moves the photo, as in Photos and Contacts.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .semantics {
                        contentDescription = positionLabel
                        customActions = actions
                    }.pointerInput(editor, cutLeft, cutTop) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            editor.pan(pan.x.toDouble(), pan.y.toDouble())
                            if (zoom != 1f) {
                                editor.zoomBy(
                                    zoom.toDouble(),
                                    (centroid.x - cutLeft).toDouble(),
                                    (centroid.y - cutTop).toDouble(),
                                )
                            }
                        }
                    },
        )

        if (!isDialog) {
            Surface(
                color = scrim.copy(alpha = HINT_ALPHA),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = HINT_BOTTOM_GAP),
            ) {
                Text(
                    text = stringResource(Res.string.edit_frame_hint),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The seven moves TalkBack users need, in reading order: four nudges, two zoom steps, and Reset. */
@Composable
private fun rememberStageActions(
    editor: FrameEditor,
    frame: ImageFrame,
): List<CustomAccessibilityAction> {
    val showTop = stringResource(Res.string.edit_frame_show_top)
    val showBottom = stringResource(Res.string.edit_frame_show_bottom)
    val showLeft = stringResource(Res.string.edit_frame_show_left)
    val showRight = stringResource(Res.string.edit_frame_show_right)
    val zoomIn = stringResource(Res.string.edit_frame_zoom_in)
    val zoomOut = stringResource(Res.string.edit_frame_zoom_out)
    val reset = stringResource(Res.string.edit_frame_reset)

    fun nudge(direction: NudgeDirection): () -> Boolean =
        {
            editor.nudge(direction, large = false)
            true
        }
    return listOf(
        CustomAccessibilityAction(showTop, nudge(NudgeDirection.Up)),
        CustomAccessibilityAction(showBottom, nudge(NudgeDirection.Down)),
        CustomAccessibilityAction(showLeft, nudge(NudgeDirection.Left)),
        CustomAccessibilityAction(showRight, nudge(NudgeDirection.Right)),
        CustomAccessibilityAction(zoomIn) {
            editor.zoomTo(frame.zoom * ZOOM_STEP)
            true
        },
        CustomAccessibilityAction(zoomOut) {
            editor.zoomTo(frame.zoom / ZOOM_STEP)
            true
        },
        CustomAccessibilityAction(reset) {
            editor.reset()
            true
        },
    )
}
